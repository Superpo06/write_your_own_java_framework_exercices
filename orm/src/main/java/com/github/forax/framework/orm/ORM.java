package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }
  // --- do not change the code above

  private static final ThreadLocal<Connection> CONNECTION = new ThreadLocal<>();

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);

    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      CONNECTION.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } catch (UncheckedSQLException e) {
        throw new SQLException(e.getCause());
      }
    } finally {
      CONNECTION.remove();
    }
  }

  static Connection currentConnection() {
    var connection = CONNECTION.get();
    if (connection == null) {
      throw new IllegalStateException("No current connexion");
    }
    return connection;
  }

  static void createTable(Class<?> beanType) throws SQLException {
    Objects.requireNonNull(beanType);
    var connection = currentConnection();
    var beanInfo = Utils.beanInfo(beanType);
    var tableName = findTableName(beanType);
    var Query = "CREATE TABLE " + tableName + " " +
        Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .map(property -> {
              var columnName = findColumnName(property);
              return columnName + " "
                  + findSQLType(property.getPropertyType())
                  + generatedValue(property)
                  + id(property, columnName);
            })
            .collect(Collectors.joining(",\n", "(", ")"));

    try (var statement = connection.createStatement()) {
      statement.executeUpdate(Query);
    }
    connection.commit();
  }

  static String findTableName(Class<?> beanType) {
    var table = beanType.getAnnotation(Table.class);
    var tableName = table == null ? beanType.getSimpleName() : table.value();
    return tableName.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor propertyDescriptor) {
    var name = propertyDescriptor.getName();
    var getter = propertyDescriptor.getReadMethod();
    if (getter == null) {
      return name;
    }
    var column = getter.getAnnotation(Column.class);
    return column == null ? name : column.value();
  }

  static String findSQLType(Class<?> type) {
    var SQLType = TYPE_MAPPING.getOrDefault(type, "VARCHAR(255)");
    return SQLType + (type.isPrimitive() ? " NOT NULL" : "");
  }

  static String generatedValue(PropertyDescriptor propertyDescriptor) {
    var getter = propertyDescriptor.getReadMethod();
    if (getter == null) {
      return "";
    }
    return getter.isAnnotationPresent(GeneratedValue.class) ? " AUTO_INCREMENT" : "";
  }

  static String id(PropertyDescriptor propertyDescriptor, String columnName) {
    var getter = propertyDescriptor.getReadMethod();
    if (getter == null) {
      return "";
    }
    return getter.isAnnotationPresent(Id.class)
        ? ",\nPRIMARY KEY (" + columnName + ")" : "";
  }

  static <R extends Repository<?, ?>> R createRepository(Class<R> repositoryClass) {
    Objects.requireNonNull(repositoryClass);
    return repositoryClass.cast(Proxy.newProxyInstance(
        repositoryClass.getClassLoader(),
        new Class<?>[]{repositoryClass},
        (_, method, args) -> {
          var connection = currentConnection();
          if (method.getDeclaringClass() == Object.class) {
            throw new UnsupportedOperationException("CHEH");
          }
          return switch (method.getName()) {
            case "toString", "hashCode", "equals" -> throw new UnsupportedOperationException("CHEH");
            case "findAll" -> {
              var beanType = findBeanTypeFromRepository(repositoryClass);
              var beanInfo = Utils.beanInfo(beanType);
              var constructor = Utils.defaultConstructor(beanType);
              var tableName = findTableName(beanType);
              try {
                yield findAll(
                    connection,
                    "SELECT * FROM " + tableName,
                    beanInfo,
                    constructor);
              } catch (SQLException e) {
                throw new UncheckedSQLException(e);
              }
            }
            default -> throw new IllegalStateException("Unknwown method name: " + method.getName());
          };
        }));
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    for (var property : beanInfo.getPropertyDescriptors()) {
      if (property.getName().equals("class")) {
        continue;
      }
      var setter = property.getWriteMethod();
      if (setter == null) {
        continue;
      }
      var columnName = findColumnName(property);
      var value = resultSet.getObject(columnName);
      Utils.invokeMethod(instance, setter, value);
    }
    return instance;
  }

  static List<?> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    var result = new ArrayList<>();
    try (var statement = connection.prepareStatement(sqlQuery);
         var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.add(toEntityClass(resultSet, beanInfo, constructor));
        }
    }
    return result;
  }
}
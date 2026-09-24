package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JSONWriter {
  static class GeneratorCache extends ClassValue<Generator> {
    @Override
    protected Generator computeValue(Class<?> type) {
      var properties = type.isRecord() ? recordProperties(type) : beanProperties(type);

      var generators = properties.stream()
          .<Generator>map(property -> {
            var name = property.getName();
            var getter = property.getReadMethod();
            var jsonProperty = getter.getAnnotation(JSONProperty.class);
            if (jsonProperty != null) {
              name = jsonProperty.value();
            }
            var prefix = "\"" + name + "\": ";
            return (writer, bean) -> {
              var value = Utils.invokeMethod(bean, getter);
              return prefix + writer.toJSON(value);
            };
          })
          .toList();

      return (writer, bean) -> generators.stream()
          .map(generator -> generator.generate(writer, bean))
          .collect(Collectors.joining(", ", "{", "}"));
    }
  }

  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static final GeneratorCache CACHE = new GeneratorCache();

  public String toJSON(Object o) {

    return switch (o) {
      case Integer _, Boolean _, Double _ -> "" + o;
      case String s -> "\"" + s + "\"";
      case null -> "null";
      case Object obj -> {
        var clazz = obj.getClass();
        var function = TYPES.computeIfAbsent(clazz, c -> {
          var generator = CACHE.get(c);
          return bean -> generator.generate(this, bean);
        });
        yield function.apply(obj);
      }
    };
  }

  private final HashMap<Class<?>, Function<Object, String>> TYPES = new HashMap<>();

  public <T> void configure(Class<T> type, Function<? super T, String> function) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(function);

    var isPresent = TYPES.putIfAbsent(type, obj -> function.apply(type.cast(obj)));
    if (isPresent != null) {
      throw new IllegalStateException("type " + type.getName() + " already configured");
    }
  }

  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
    return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
        .filter(property -> !property.getName().equals("class"))
        .filter(property -> property.getReadMethod() != null)
        .toList();
  }

  private static List<PropertyDescriptor> recordProperties(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(component -> {
          try {
            return new PropertyDescriptor(
                component.getName(),
                component.getAccessor(),
                null
            );
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        })
        .toList();
  }
}

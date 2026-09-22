package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class InjectorRegistry {
    public InjectorRegistry() {
        suppliers = new HashMap<>();
        super();
    }
    private final HashMap<Class<?>, Supplier<?>> suppliers;
    public <T> T lookupInstance(Class<T> type){
        Objects.requireNonNull(type);
        var supplier = suppliers.get(type);
        if(supplier == null){
            throw new IllegalStateException("no instance registered for type " + type.getName());
        }
        return type.cast(supplier.get());
    }
    public <T> void registerInstance(Class<T> type, T object){
        Objects.requireNonNull(type);
        Objects.requireNonNull(object);
        registerProvider(type, () -> object);
    }
    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier){
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        var result = suppliers.putIfAbsent(type, supplier);
        if(result != null){
            throw new IllegalStateException("instance already registered for type " + type.getName());
        }
    }
    public static <T> List<PropertyDescriptor> findInjectableProperties(Class<T> type){
        var beanInfo = Utils.beanInfo(type);
        var propertyDescriptors = beanInfo.getPropertyDescriptors();
        return Arrays.stream(propertyDescriptors)
                .filter(property -> {
                    var setter = property.getWriteMethod();
                    return setter != null && setter.isAnnotationPresent(Inject.class);
                })
                .toList();
    }
    public <T> void registerProviderClass(Class<T> type, Class<? extends T> clazz) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(clazz);
        var constructor = Utils.defaultConstructor(clazz);
        var properties = InjectorRegistry.findInjectableProperties(clazz);
        registerProvider(type, () -> {
            var instance = Utils.newInstance(constructor);
            for(var property : properties){
                var setter = property.getWriteMethod();
                var value = lookupInstance(property.getPropertyType());
                Utils.invokeMethod(instance, setter, value);
            }
            return instance;
        });
    }
}
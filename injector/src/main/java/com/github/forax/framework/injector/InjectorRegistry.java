package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
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
        var constructor = InjectorRegistry.findConstructor(clazz);
        var properties = InjectorRegistry.findInjectableProperties(clazz);
        registerProvider(type, () -> {
            var args = Arrays.stream(constructor.getParameterTypes()).map(this::lookupInstance).toArray();
            var instance = Utils.newInstance(constructor, args);
            for(var property : properties){
                var setter = property.getWriteMethod();
                var value = lookupInstance(property.getPropertyType());
                Utils.invokeMethod(instance, setter, value);
            }
            return type.cast(instance);
        });
    }

    private <T> void registerProviderClassInternal(Class<T> type) {
        registerProviderClass(type, type);
    }

    public void registerProviderClass(Class<?> type) {
        Objects.requireNonNull(type);
        registerProviderClassInternal(type);
    }

    private static <T> Constructor<?> findConstructor(Class<? extends T> type) {
        var constructors = type.getConstructors();
        var foundedConstructors = Arrays.stream(constructors)
                .filter(constructor -> {
                    return constructor.isAnnotationPresent(Inject.class);}).toList();
        return switch (foundedConstructors.size()) {
            case 0 -> Utils.defaultConstructor(type);
            case 1->  foundedConstructors.getFirst();
            default -> throw new IllegalStateException("Either no or multiple constructor candidates found");
        };
    }
}
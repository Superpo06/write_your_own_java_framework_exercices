package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public final class JSONWriter {
    static class PropertyDescriptorsCache extends ClassValue<PropertyDescriptor[]> {
        @Override
        protected PropertyDescriptor[] computeValue(Class<?> type) {
            var beanInfo = Utils.beanInfo(type);
            return beanInfo.getPropertyDescriptors();
        }
    }



    private static final PropertyDescriptorsCache CACHE = new PropertyDescriptorsCache();
    public String toJSON(Object o) {
    return switch(o){
        case Integer _, Boolean _, Double _ -> "" + o;
        case String s -> "\"" + s + "\"";
        case null -> "null";
        case Object obj -> {
            var properties = CACHE.get(obj.getClass());
            yield Arrays.stream(properties)
                    .filter(property -> !property.getName().equals("class"))
                    .filter(property -> property.getReadMethod() != null)
                    .map(property -> {
                        var name = property.getName();
                        var getter = property.getReadMethod();
                        var value = Utils.invokeMethod(obj, getter);
                        return "\"" + name + "\": " + toJSON(value);
                    })
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    };
  }
}

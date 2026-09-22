package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public final class JSONWriter {
    static class GeneratorCache extends ClassValue<Generator> {
        @Override
        protected Generator computeValue(Class<?> type) {
            var beanInfo = Utils.beanInfo(type);
            var generators = Arrays.stream(beanInfo.getPropertyDescriptors())
                    .filter(property -> !property.getName().equals("class"))
                    .filter(property -> property.getReadMethod() != null)
                    .<Generator>map(property ->{
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
        String generate(JSONWriter writer, Object Bean);
    }

    private static final GeneratorCache CACHE = new GeneratorCache();
    public String toJSON(Object o) {
    return switch(o){
        case Integer _, Boolean _, Double _ -> "" + o;
        case String s -> "\"" + s + "\"";
        case null -> "null";
        case Object obj -> {
            var generator = CACHE.get(obj.getClass());
            yield generator.generate(this, obj);
        }
    };
  }
}

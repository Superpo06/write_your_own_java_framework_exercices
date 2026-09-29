package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Arrays.stream;

public final class InterceptorRegistry {
  public InterceptorRegistry() {
    this.registryMap = new HashMap<>();
    this.invocationCache = new HashMap<>();
    super();
  }
  private final HashMap<Class<?>, List<Interceptor>> registryMap;

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice advice){
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(advice);
    addInterceptor(annotationClass, (instance, method, args, invocation) ->{
      advice.before(instance, method, args);
      var result = invocation.proceed(instance, method, args);
      advice.after(instance, method, args, result);
      return result;
    });
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor){
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(interceptor);
    registryMap.computeIfAbsent(annotationClass, _ -> new LinkedList<>()).add(interceptor);
    invocationCache.clear();
  }

  //  List<AroundAdvice> findAdvices(Method method) {
//    return Arrays.stream(method.getAnnotations())
//        .flatMap(a -> registryMap.getOrDefault(a.annotationType(), List.of()).stream())
//        .toList();
//  }

  public List<Interceptor> findInterceptors(Method method){
    return Stream.of(
            stream(method.getDeclaringClass().getAnnotations()),
            stream(method.getAnnotations()),
            stream(method.getParameterAnnotations()).flatMap(Arrays::stream))
        .flatMap(s-> s)
        .distinct()
        .flatMap(a -> registryMap.getOrDefault(a.annotationType(), List.of()).stream())
        .toList();
  }

  public static Invocation getInvocation(List<Interceptor> interceptors) {
    Invocation invoke = Utils::invokeMethod;
    for(var interceptor : interceptors.reversed()){
      Invocation finalInvoke = invoke;
      invoke = (instance, method, args) ->
          interceptor.intercept(instance, method, args, finalInvoke);
    }
    return invoke;
  }

  private final HashMap<Method, Invocation> invocationCache;

  public <T> T createProxy(Class<T> type, T delegate) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    return type.cast(Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[]{type},
        (proxy, method, args) -> {
          var invocation = invocationCache.computeIfAbsent(method, m -> getInvocation(findInterceptors(m)));
          return invocation.proceed(delegate, method, args);
        }));
  }
}

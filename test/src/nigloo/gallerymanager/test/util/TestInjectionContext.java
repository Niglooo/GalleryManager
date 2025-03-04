package nigloo.gallerymanager.test.util;

import nigloo.tool.injection.InjectionContext;

import java.util.concurrent.ConcurrentHashMap;

public class TestInjectionContext implements InjectionContext
{
    private final ConcurrentHashMap<Class<?>, Object> instances = new ConcurrentHashMap<>();

    @Override
    public <T> T getInstance(Class<T> clazz)
    {
        Object instance = instances.get(clazz);
        if (instance == null) {
            throw new IllegalStateException("No instance to inject of type " + clazz.getSimpleName());
        }

        return (T) instance;
    }

    public void setInstance(Object instance) {
        setInstance((Class<Object>) instance.getClass(), instance);
    }

    public <T> void setInstance(Class<T> clazz, T instance) {
        instances.put(clazz, instance);
    }

    public void clear() {
        instances.clear();
    }
}

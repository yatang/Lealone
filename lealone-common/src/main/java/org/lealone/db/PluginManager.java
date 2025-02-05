/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db;

import java.util.Collection;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;

public class PluginManager<T extends Plugin> {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    private final Class<T> pluginClass;
    private final Map<String, T> plugins = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    protected PluginManager(Class<T> pluginClass) {
        this.pluginClass = pluginClass;
    }

    public T getPlugin(String name) {
        if (name == null)
            throw new NullPointerException("name is null");
        if (!loaded)
            loadPlugins();
        return plugins.get(name.toUpperCase());
    }

    public Collection<T> getPlugins() {
        return plugins.values();
    }

    public void registerPlugin(T plugin, String... alias) {
        plugins.put(plugin.getName().toUpperCase(), plugin);
        plugins.put(plugin.getClass().getName().toUpperCase(), plugin);
        if (alias != null) {
            for (String a : alias)
                plugins.put(a.toUpperCase(), plugin);
        }
    }

    public void deregisterPlugin(T plugin, String... alias) {
        plugins.remove(plugin.getName().toUpperCase());
        plugins.remove(plugin.getClass().getName().toUpperCase().toUpperCase());
        if (alias != null) {
            for (String a : alias)
                plugins.remove(a.toUpperCase());
        }
    }

    private synchronized void loadPlugins() {
        if (loaded)
            return;
        try {
            // 执行next时ServiceLoader内部会自动为每一个实现Plugin接口的类生成一个新实例
            // 所以Plugin接口的实现类必需有一个public的无参数构造函数
            for (T p : ServiceLoader.load(pluginClass)) {
                registerPlugin(p);
            }
        } catch (Throwable t) {
            // 只是发出警告
            logger.warn("Failed to load plugin: " + pluginClass.getName(), t);
        }
        // 注意在load完之后再设为true，否则其他线程可能会因为不用等待load完成从而得到一个NPE
        loaded = true;
    }

    private static final Map<Class<?>, PluginManager<?>> instances = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static <P extends Plugin> PluginManager<P> getInstance(P plugin) {
        return (PluginManager<P>) getInstance(plugin.getClass());
    }

    @SuppressWarnings("unchecked")
    private static <P extends Plugin> PluginManager<P> getInstance(Class<P> pluginClass) {
        PluginManager<?> instance = instances.get(pluginClass);
        if (instance == null) {
            instance = new PluginManager<>(pluginClass);
            PluginManager<?> old = instances.putIfAbsent(pluginClass, instance);
            if (old != null) {
                instance = old;
            }
        }
        return (PluginManager<P>) instance;
    }

    public static <P extends Plugin> P getPlugin(Class<P> pluginClass, String name) {
        return getInstance(pluginClass).getPlugin(name);
    }

    public static <P extends Plugin> Collection<P> getPlugins(Class<P> pluginClass) {
        return getInstance(pluginClass).getPlugins();
    }

    public static <P extends Plugin> void register(P plugin, String... alias) {
        getInstance(plugin).registerPlugin(plugin, alias);
    }

    public static <P extends Plugin> void deregister(P plugin, String... alias) {
        getInstance(plugin).deregisterPlugin(plugin, alias);
    }
}

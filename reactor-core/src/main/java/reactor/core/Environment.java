/*
 * Copyright (c) 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.LoggerFactory;
import reactor.convert.StandardConverters;
import reactor.fn.Registration;
import reactor.fn.registry.CachingRegistry;
import reactor.fn.registry.Registry;
import reactor.fn.dispatch.BlockingQueueDispatcher;
import reactor.fn.dispatch.Dispatcher;
import reactor.fn.dispatch.RingBufferDispatcher;
import reactor.fn.dispatch.ThreadPoolExecutorDispatcher;

import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static reactor.Fn.$;

/**
 * @author Stephane Maldini
 * @author Jon Brisbin
 */
public class Environment {

	private static final ClassLoader CL = Environment.class.getClassLoader();

	private static final String REACTOR_PREFIX      = "reactor.";
	private static final String PROFILES_ACTIVE     = "reactor.profiles.active";
	private static final String PROFILES_DEFAULT    = "reactor.profiles.default";
	private static final String DISPATCHERS_NAME    = "reactor.dispatchers.%s.name";
	private static final String DISPATCHERS_SIZE    = "reactor.dispatchers.%s.size";
	private static final String DISPATCHERS_BACKLOG = "reactor.dispatchers.%s.backlog";

	public static final String THREAD_POOL_EXECUTOR_DISPATCHER = "threadPoolExecutor";
	public static final String EVENT_LOOP_DISPATCHER           = "eventLoop";
	public static final String RING_BUFFER_DISPATCHER          = "ringBuffer";
	public static final String DEFAULT_DISPATCHER              = "default";

	public static final int PROCESSORS = Runtime.getRuntime().availableProcessors();

	private final Properties               env           = new Properties();
	private final AtomicReference<Reactor> sharedReactor = new AtomicReference<Reactor>();
	private final Registry<Reactor>        reactors      = new CachingRegistry<Reactor>(null, null);
	private final Registry<Dispatcher> dispatcherSuppliers;
	private final String               defaultDispatcher;

	public Environment() {
		this(new CachingRegistry<Dispatcher>(Registry.LoadBalancingStrategy.ROUND_ROBIN, null));
	}

	public Environment(Registry<Dispatcher> dispatcherSuppliers) {
		this.dispatcherSuppliers = dispatcherSuppliers;

		String defaultProfileName = System.getProperty(PROFILES_DEFAULT, getDefaultProfile());
		Map<Object, Object> props = loadProfile(defaultProfileName);
		if (null != props) {
			env.putAll(props);
		}

		if (null != System.getProperty(PROFILES_ACTIVE)) {
			String[] profiles = System.getProperty(PROFILES_ACTIVE).split(",");
			for (String profile : profiles) {
				props = loadProfile(profile);
				if (null != props) {
					env.putAll(props);
				}
			}
		}

		for (String prop : System.getProperties().stringPropertyNames()) {
			if (prop.startsWith(REACTOR_PREFIX)) {
				env.put(prop, System.getProperty(prop));
			}
		}

		defaultDispatcher = env.getProperty(String.format(DISPATCHERS_NAME, DEFAULT_DISPATCHER), RING_BUFFER_DISPATCHER);

		DispatcherConfig threadPoolConfig = new DispatcherConfig(THREAD_POOL_EXECUTOR_DISPATCHER, 128);
		DispatcherConfig eventLoopConfig = new DispatcherConfig(EVENT_LOOP_DISPATCHER, 128);
		DispatcherConfig ringBufferConfig = new DispatcherConfig(RING_BUFFER_DISPATCHER, 1024);

		if (threadPoolConfig.size > 0) {
			addDispatcher(threadPoolConfig.dispatcherName,
					new ThreadPoolExecutorDispatcher(threadPoolConfig.size, threadPoolConfig.backlog));
		}
		if (eventLoopConfig.size > 0) {
			addDispatcher(eventLoopConfig.dispatcherName,
					new BlockingQueueDispatcher(eventLoopConfig.dispatcherName, eventLoopConfig.backlog));
		}
		if (ringBufferConfig.size > 0) {
			addDispatcher(ringBufferConfig.dispatcherName,
					new RingBufferDispatcher(ringBufferConfig.dispatcherName,
							ringBufferConfig.size,
							ringBufferConfig.backlog,
							ProducerType.MULTI,
							new BlockingWaitStrategy()));
		}
	}

	private class DispatcherConfig {
		final int    size;
		final int    backlog;
		final String dispatcherName;

		private DispatcherConfig(String dispatcherAlias, int defaultBacklog) {
			dispatcherName = env.getProperty(String.format(DISPATCHERS_NAME, dispatcherAlias));
			if (null != dispatcherName) {
				int _size = getProperty(String.format(DISPATCHERS_SIZE, dispatcherName), Integer.class, PROCESSORS);
				size = _size < 1 ? PROCESSORS : _size;
				backlog = getProperty(String.format(DISPATCHERS_BACKLOG, dispatcherName), Integer.class, defaultBacklog);
			} else {
				size = -1;
				backlog = -1;
			}
		}
	}

	public String getProperty(String key, String defaultValue) {
		return env.getProperty(key, defaultValue);
	}

	@SuppressWarnings("unchecked")
	public <T> T getProperty(String key, Class<T> type, T defaultValue) {
		if (env.containsKey(key)) {
			Object val = env.getProperty(key);
			if (null == val) {
				return defaultValue;
			}
			if (!type.isAssignableFrom(val.getClass()) && StandardConverters.CONVERTERS.canConvert(String.class, type)) {
				return StandardConverters.CONVERTERS.convert(val, type);
			} else {
				return (T) val;
			}
		}
		return defaultValue;
	}

	public Dispatcher getDispatcher(String name) {
		Iterator<Registration<? extends Dispatcher>> regs = dispatcherSuppliers.select(name).iterator();
		if (!regs.hasNext()) {
			throw new IllegalArgumentException("No DispatcherSupplier found for name '" + name + "'");
		}
		return regs.next().getObject();
	}

	public Environment addDispatcher(String name, Dispatcher dispatcher) {
		if (defaultDispatcher.equals(name))
			dispatcherSuppliers.register($(DEFAULT_DISPATCHER), dispatcher);
		dispatcherSuppliers.register($(name), dispatcher);
		return this;
	}

	public Environment removeDispatcher(String name) {
		dispatcherSuppliers.unregister(name);
		return this;
	}

	public Registration<? extends Reactor> register(Reactor reactor) {
		return reactors.register($(reactor.getId()), reactor);
	}

	public Reactor find(String id) {
		return find(UUID.fromString(id));
	}

	public Reactor find(UUID id) {
		Iterator<Registration<? extends Reactor>> rs = reactors.select(id).iterator();
		if (!rs.hasNext()) {
			return null;
		}

		Reactor r = null;
		while (rs.hasNext()) {
			r = rs.next().getObject();
		}
		return r;
	}

	public boolean unregister(String id) {
		return unregister(UUID.fromString(id));
	}

	public boolean unregister(UUID id) {
		return reactors.unregister(id);
	}


	public Reactor getSharedReactor() {
		sharedReactor.compareAndSet(null, new Reactor(this, new BlockingQueueDispatcher("shared", 128)));
		return sharedReactor.get();
	}

	protected String getDefaultProfile() {
		return "default";
	}

	protected Properties loadProfile(String name) {
		Properties props = new Properties();
		String propsName = String.format("META-INF/reactor/%s.properties", name);
		URL propsUrl = CL.getResource(propsName);
		if (null != propsUrl) {
			try {
				props.load(propsUrl.openStream());
			} catch (IOException e) {
				LoggerFactory.getLogger(getClass()).error(e.getMessage(), e);
			}
		} else {
			LoggerFactory.getLogger(getClass()).debug("No properties file found in the classpath at " + propsName + " for profile '" + name + "'");
		}
		return props;
	}

}
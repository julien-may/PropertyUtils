package ch.julien.propertyutils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.core.NamingPolicy;
import net.sf.cglib.core.Predicate;
import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.CallbackFilter;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.Factory;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import net.sf.cglib.proxy.NoOp;

public class PropertyUtils {
	private static final Map<InvocationSequence, Object> PLACEHOLDER_BY_INVOCATION = new WeakHashMap<InvocationSequence, Object>();
	private static final AtomicInteger PLACEHOLDER_COUNTER = new AtomicInteger(Integer.MIN_VALUE);
	private static final Map<Object, Argument<?>> ARGUMENTS_BY_PLACEHOLDER = new WeakHashMap<Object, Argument<?>>();

	private static final ThreadLocal<LimitedValuesArgumentHolder> LIMITED_VALUE_ARGUMENTS = new ThreadLocal<LimitedValuesArgumentHolder>() {
		protected LimitedValuesArgumentHolder initialValue() {
			return new LimitedValuesArgumentHolder();
		}
	};

	public static <T> T on(Class<T> clazz) {
		return on(clazz, new InvocationSequence(clazz));
	}

	public static <T> Argument<T> property(T placeholder) {
		Argument<T> actualArgument = placeholderToArgument(placeholder);
		if (actualArgument == null) {
			throw new RuntimeException();
			//throw new ArgumentConversionException("Unable to convert the placeholder " + placeholder + " in a valid argument");
		}
		return actualArgument;
	}

	private static <T> Argument<T> placeholderToArgument(T placeholder) {
		if (placeholder instanceof Argument) return (Argument<T>)placeholder;
		return (Argument<T>)(isLimitedValues(placeholder) ? LIMITED_VALUE_ARGUMENTS.get().getArgument(placeholder) : ARGUMENTS_BY_PLACEHOLDER.get(placeholder));
	}

	static synchronized <T> T on(Class<T> clazz, InvocationSequence invocationSequence) {
		T placeholder = (T) PLACEHOLDER_BY_INVOCATION.get(invocationSequence);

		if (placeholder == null) {
			placeholder = registerNewArgument(clazz, invocationSequence);
		}

		else if (isLimitedValues(placeholder)) LIMITED_VALUE_ARGUMENTS.get().setArgument(placeholder, new Argument<T>(invocationSequence));
		return placeholder;
	}

	private static <T> T registerNewArgument(Class<T> clazz, InvocationSequence invocationSequence) {
		T placeholder = (T)createPlaceholder(clazz, invocationSequence);
		PLACEHOLDER_BY_INVOCATION.put(invocationSequence, placeholder);
		bindArgument(placeholder, new Argument<T>(invocationSequence));
		return placeholder;
	}

	private static <T> void bindArgument(T placeholder, Argument<T> argument) {
		if (isLimitedValues(placeholder)) LIMITED_VALUE_ARGUMENTS.get().setArgument(placeholder, argument);
		else ARGUMENTS_BY_PLACEHOLDER.put(placeholder, argument);
	}

	private static Object createPlaceholder(Class<?> clazz, InvocationSequence invocationSequence) {
		return !Modifier.isFinal(clazz.getModifiers()) ?
			ProxyUtil.createProxy(new ProxyArgument(clazz, invocationSequence), clazz, false) :
			createArgumentPlaceholder(clazz);
	}

	static Object createArgumentPlaceholder(Class<?> clazz) {
		return isLimitedValues(clazz) ? LIMITED_VALUE_ARGUMENTS.get().getNextPlaceholder(clazz) : createArgumentPlaceholder(clazz, PLACEHOLDER_COUNTER.addAndGet(1));
	}

	private static Object createArgumentPlaceholder(Class<?> clazz, Integer placeholderId) {
		if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz) || Character.class == clazz)
			return getPrimitivePlaceHolder(clazz, placeholderId);

		if (clazz == String.class) return String.valueOf(placeholderId);
		if (Date.class.isAssignableFrom(clazz)) return new Date(placeholderId);
		if (clazz.isArray()) return Array.newInstance(clazz.getComponentType(), 1);

		try {
			return createArgumentPlaceholderForUnknownClass(clazz, placeholderId);
		} catch (Exception e) {
			throw new RuntimeException();
			//throw new ArgumentConversionException("It is not possible to create a placeholder for class: " + clazz.getName(), e);
		}
	}

	private static Object createArgumentPlaceholderForUnknownClass(Class<?> clazz, Integer placeholderId) throws IllegalAccessException, InstantiationException {
		for (Constructor constructor : clazz.getConstructors()) {
			Class<?>[] params = constructor.getParameterTypes();
			if (params.length != 1) continue;
			try {
				if (params[0] == String.class) return constructor.newInstance(String.valueOf(placeholderId));
				if (isNumericClass(params[0])) return constructor.newInstance(placeholderId);
			} catch (IllegalAccessException e1) {
			} catch (InvocationTargetException e2) {
			}
		}
		return clazz.newInstance();
	}

	private static boolean isNumericClass(Class<?> clazz) {
		return isInt(clazz) || isLong(clazz);
	}

	private static Object getPrimitivePlaceHolder(Class<?> clazz, Integer placeholderId) {
		if (isInt(clazz)) return placeholderId;
		if (isLong(clazz)) return placeholderId.longValue();
		if (isDouble(clazz)) return placeholderId.doubleValue();
		if (isFloat(clazz)) return placeholderId.floatValue();
		if (isCharacter(clazz)) return Character.forDigit(placeholderId % Character.MAX_RADIX, Character.MAX_RADIX);
		if (isShort(clazz)) return placeholderId.shortValue();
		return placeholderId.byteValue();
	}

	private static boolean isInt(Class<?> clazz) {
		return clazz == Integer.TYPE || clazz == Integer.class;
	}

	private static boolean isLong(Class<?> clazz) {
		return clazz == Long.TYPE || clazz == Long.class;
	}

	private static boolean isDouble(Class<?> clazz) {
		return clazz == Double.TYPE || clazz == Double.class;
	}

	private static boolean isFloat(Class<?> clazz) {
		return clazz == Float.TYPE || clazz == Float.class;
	}

	private static boolean isCharacter(Class<?> clazz) {
		return clazz == Character.TYPE || clazz == Character.class;
	}

	private static boolean isShort(Class<?> clazz) {
		return clazz == Short.TYPE || clazz == Short.class;
	}

	private static boolean isLimitedValues(Object placeholder) {
		return placeholder != null && isLimitedValues(placeholder.getClass());
	}

	private static boolean isLimitedValues(Class<?> clazz) {
		return clazz == Boolean.TYPE || clazz == Boolean.class || clazz.isEnum();
	}


	static final class InvocationSequence {
		private static final long serialVersionUID = 1L;

		private final Class<?> rootInvokedClass;
		private String inkvokedPropertyName;
		private Invocation lastInvocation;
		private transient int hashCode;

		InvocationSequence(Class<?> rootInvokedClass) {
			this.rootInvokedClass = rootInvokedClass;
		}

		InvocationSequence(InvocationSequence sequence, Invocation invocation) {
			this(sequence.getRootInvokedClass());
			invocation.previousInvocation = sequence.lastInvocation;
			lastInvocation = invocation;
		}

		Class<?> getRootInvokedClass() {
			return rootInvokedClass;
		}

		String getInkvokedPropertyName() {
			if (inkvokedPropertyName == null) inkvokedPropertyName = calcInkvokedPropertyName();
			return inkvokedPropertyName;
		}

		private String calcInkvokedPropertyName() {
			if (null == lastInvocation) return "";
			StringBuilder sb = new StringBuilder();

			calcInkvokedPropertyName(lastInvocation, lastInvocation.previousInvocation, sb);
			return sb.substring(1);
		}

		private void calcInkvokedPropertyName(Invocation inv, Invocation prevInv, StringBuilder sb) {
			if (prevInv != null) {
				calcInkvokedPropertyName(prevInv, prevInv.previousInvocation, sb);
			}
			sb.append(".").append(inv.getInvokedPropertyName());
		}

		Class<?> getReturnType() {
			return lastInvocation.getReturnType();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean equals(Object object) {
			return object != null && rootInvokedClass == ((InvocationSequence)object).rootInvokedClass
				&& Invocation.areNullSafeEquals(lastInvocation, ((InvocationSequence)object).lastInvocation);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int hashCode() {
			if (hashCode != 0) return hashCode;
			hashCode = 13 * rootInvokedClass.hashCode();
			int factor = 17;
			for (Invocation invocation = lastInvocation; invocation != null; invocation = invocation.previousInvocation) {
				hashCode += factor * invocation.hashCode();
				factor += 2;
			}
			return hashCode;
		}

		@SuppressWarnings("unchecked")
		public <T> T invokeOn(Object object) {
			return (T)invokeOn(lastInvocation, object);
		}

		private Object invokeOn(Invocation invocation, Object value) {
			if (invocation == null) return value;
			if (invocation.previousInvocation != null) value = invokeOn(invocation.previousInvocation, value);
			return invocation.invokeOn(value);
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder(100);
			sb.append("[");
			if (lastInvocation == null) sb.append(rootInvokedClass);
			else toString(lastInvocation, lastInvocation.previousInvocation, sb, true);
			sb.append("]");
			return sb.toString();
		}

		private void toString(Invocation inv, Invocation prevInv, StringBuilder sb, boolean first) {
			if (prevInv != null) toString(prevInv, prevInv.previousInvocation, sb, false);
			sb.append(inv);
			if (!first) sb.append(", ");
		}
	}

	static final class Invocation {

		private final Class<?> invokedClass;
		private final Method invokedMethod;
		private String invokedPropertyName;
		private ParameterReference[] weakArgs;
		private transient int hashCode;
		Invocation previousInvocation;

		Invocation(Class<?> invokedClass, Method invokedMethod, Object[] args) {
			this.invokedClass = invokedClass;
			this.invokedMethod = invokedMethod;
			invokedMethod.setAccessible(true);
			if (args != null && args.length > 0) {
				weakArgs = new ParameterReference[args.length];
				for (int i = 0; i < args.length; i++) {
					weakArgs[i] = invokedMethod.getParameterTypes()[i].isPrimitive() ? new StrongParameterReference(args[i]) : new WeakParameterReference(args[i]);
				}
			}
		}

		private Object[] getConcreteArgs() {
			if (weakArgs == null) return new Object[0];
			Object[] args = new Object[weakArgs.length];
			for (int i = 0; i < weakArgs.length; i++) {
				args[i] = weakArgs[i].get();
			}
			return args;
		}

		Class<?> getInvokedClass() {
			return invokedClass;
		}

		Method getInvokedMethod() {
			return invokedMethod;
		}

		Class<?> getReturnType() {
			return invokedMethod.getReturnType();
		}

		String getInvokedPropertyName() {
			if (invokedPropertyName == null) invokedPropertyName = IntrospectionUtil.getPropertyName(invokedMethod);
			return invokedPropertyName;
		}

		Object invokeOn(Object object) {
			try {
				return object == null ? null : invokedMethod.invoke(object, getConcreteArgs());
			} catch (Exception e) {
				//throw new InvocationException(e, invokedMethod, object);
				throw new RuntimeException();
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder(invokedMethod.toString());
			if (weakArgs != null) {
				sb.append(" with args ");
				boolean first = true;
				for (ParameterReference arg : weakArgs) {
					sb.append(first ? "" : ", ").append(arg.get());
					first = false;
				}
			}
			return sb.toString();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int hashCode() {
			if (hashCode != 0) return hashCode;
			hashCode = 13 * invokedClass.hashCode() + 17 * invokedMethod.hashCode();
			if (weakArgs != null) hashCode += 19 * weakArgs.length;
			if (previousInvocation != null) hashCode += 23 * previousInvocation.hashCode();
			return hashCode;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean equals(Object object) {
			Invocation otherInvocation = (Invocation)object;
			return areNullSafeEquals(invokedClass, otherInvocation.getInvokedClass()) &&
				areNullSafeEquals(invokedMethod, otherInvocation.getInvokedMethod()) &&
				areNullSafeEquals(previousInvocation, otherInvocation.previousInvocation) &&
				Arrays.equals(weakArgs, otherInvocation.weakArgs);
		}

		static boolean areNullSafeEquals(Object first, Object second) {
			return first == second || (first != null && second != null && first.equals(second));
		}

		private static abstract class ParameterReference {
			protected abstract Object get();

			@Override
			public final boolean equals(Object obj) {
				return obj instanceof ParameterReference && areNullSafeEquals(get(), ((ParameterReference)obj).get());
			}
		}

		private static final class StrongParameterReference extends ParameterReference {
			private final Object strongRef;

			private StrongParameterReference(Object referent) {
				strongRef = referent;
			}

			protected Object get() {
				return strongRef;
			}
		}

		private static final class WeakParameterReference extends ParameterReference {
			private final WeakReference<Object> weakRef;

			private WeakParameterReference(Object referent) {
				weakRef = new WeakReference<Object>(referent);
			}

			protected Object get() {
				return weakRef.get();
			}
		}
	}

	static class ProxyArgument extends InvocationInterceptor {

		private final Class<?> proxiedClass;

		private final WeakReference<InvocationSequence> invocationSequence;

		ProxyArgument(Class<?> proxiedClass, InvocationSequence invocationSequence) {
			this.proxiedClass = proxiedClass;
			this.invocationSequence = new WeakReference<InvocationSequence>(invocationSequence);
		}

		/**
		 * {@inheritDoc}
		 */
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getName().equals("hashCode")) return invocationSequence.hashCode();
			if (method.getName().equals("equals")) return invocationSequence.equals(args[0]);

			// Adds this invocation to the current invocation sequence and creates a new proxy propagating the invocation sequence
			return on(method.getReturnType(), new InvocationSequence(invocationSequence.get(), new Invocation(proxiedClass, method, args)));
		}
	}

	public static abstract class InvocationInterceptor implements MethodInterceptor, java.lang.reflect.InvocationHandler {

		/**
		 * {@inheritDoc}
		 */
		public final Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			return invoke(proxy, method, args);
		}
	}

	private static final class LimitedValuesArgumentHolder {

		private boolean booleanPlaceholder = true;
		private final Argument<?>[] booleanArguments = new Argument[2];

		private int enumPlaceholder = 0;
		private final Map<Object, Argument<?>> enumArguments = new HashMap<Object, Argument<?>>();

		private int booleanToInt(Object placeholder) {
			return (Boolean)placeholder ? 1 : 0;
		}

		void setArgument(Object placeholder, Argument<?> argument) {
			if (placeholder.getClass().isEnum()) enumArguments.put(placeholder, argument);
			else booleanArguments[booleanToInt(placeholder)] = argument;
		}

		Argument<?> getArgument(Object placeholder) {
			return placeholder.getClass().isEnum() ? enumArguments.get(placeholder) : booleanArguments[booleanToInt(placeholder)];
		}

		@SuppressWarnings("unchecked")
		Object getNextPlaceholder(Class<?> clazz) {
			return clazz.isEnum() ? getNextEnumPlaceholder((Class<? extends Enum>)clazz) : getNextBooleanPlaceholder();
		}

		private boolean getNextBooleanPlaceholder() {
			booleanPlaceholder = !booleanPlaceholder;
			return booleanPlaceholder;
		}

		private <E extends Enum<E>> Enum<E> getNextEnumPlaceholder(Class<E> clazz) {
			List<E> enums = new ArrayList<E>(EnumSet.allOf(clazz));
			return enums.get(enumPlaceholder++ % enums.size());
		}
	}


	public static final class ProxyUtil {

		private ProxyUtil() { }

		// ////////////////////////////////////////////////////////////////////////
		// /// Generic Proxy
		// ////////////////////////////////////////////////////////////////////////

		/**
		 * Check if the given class is nor final neither a primitive one
		 * @param clazz The class to be checked
		 * @return True if the class is proxable, false otherwise
		 */
		public static boolean isProxable(Class<?> clazz) {
			return !clazz.isPrimitive() && !Modifier.isFinal(clazz.getModifiers()) && !clazz.isAnonymousClass();
		}

		/**
		 * Creates a dynamic proxy
		 * @param interceptor The interceptor that manages the invocations to the created proxy
		 * @param clazz The class to be proxied
		 * @param failSafe If true return null if it is not possible to proxy the request class, otherwise throws an UnproxableClassException
		 * @param implementedInterface The interfaces that has to be implemented by the new proxy
		 * @return The newly created proxy
		 */
		public static <T> T createProxy(InvocationInterceptor interceptor, Class<T> clazz, boolean failSafe, Class<?> ... implementedInterface) {
			if (clazz.isInterface()) return (T)createNativeJavaProxy(clazz.getClassLoader(), interceptor, concatClasses(new Class<?>[] { clazz }, implementedInterface));
			final ProxyIterator proxyIterator = (interceptor instanceof ProxyIterator) ? (ProxyIterator)interceptor : null;
			try {
				if (proxyIterator != null) proxyIterator.enabled = false;
				return (T)createEnhancer(interceptor, clazz, implementedInterface).create();
			} catch (IllegalArgumentException iae) {
				if (Proxy.isProxyClass(clazz)) return (T)createNativeJavaProxy(clazz.getClassLoader(), interceptor, concatClasses(implementedInterface, clazz.getInterfaces()));
				if (isProxable(clazz)) return ClassImposterizer.INSTANCE.imposterise(interceptor, clazz, implementedInterface);
				return manageUnproxableClass(clazz, failSafe);
			} finally {
				if (proxyIterator != null) proxyIterator.enabled = true;
			}
		}

		private static <T> T manageUnproxableClass(Class<T> clazz, boolean failSafe) {
			if (failSafe) return null;
//			throw new UnproxableClassException(clazz);
			throw new RuntimeException();
		}

		// ////////////////////////////////////////////////////////////////////////
		// /// Iterable Proxy
		// ////////////////////////////////////////////////////////////////////////

		/**
		 * Creates a proxy of the given class that also decorates it with Iterable interface
		 * @param interceptor The object that will intercept all the invocations to the returned proxy
		 * @param clazz The class to be proxied
		 * @return The newly created proxy
		 */
		public static <T> T createIterableProxy(InvocationInterceptor interceptor, Class<T> clazz) {
			if (clazz.isPrimitive()) return null;
			return createProxy(interceptor, normalizeProxiedClass(clazz), false, Iterable.class);
		}

		private static <T> Class<T> normalizeProxiedClass(Class<T> clazz) {
			if (clazz == String.class) return (Class<T>)CharSequence.class;
			return clazz;
		}

		// ////////////////////////////////////////////////////////////////////////
		// /// Private
		// ////////////////////////////////////////////////////////////////////////

		private static Enhancer createEnhancer(MethodInterceptor interceptor, Class<?> clazz, Class<?> ... interfaces) {
			Enhancer enhancer = new Enhancer();
			enhancer.setCallback(interceptor);
			enhancer.setSuperclass(clazz);
			if (interfaces != null && interfaces.length > 0) enhancer.setInterfaces(interfaces);
			return enhancer;
		}

		private static Object createNativeJavaProxy(ClassLoader classLoader, InvocationHandler interceptor, Class<?> ... interfaces) {
			return Proxy.newProxyInstance(classLoader, interfaces, interceptor);
		}

		private static Class<?>[] concatClasses(Class<?>[] first, Class<?>[] second) {
			if (first == null || first.length == 0) return second;
			if (second == null || second.length == 0) return first;
			Class<?>[] concatClasses = new Class[first.length + second.length];
			System.arraycopy(first, 0, concatClasses, 0, first.length);
			System.arraycopy(second, 0, concatClasses, first.length, second.length);
			return concatClasses;
		}
	}


	public static class ProxyIterator<T> extends InvocationInterceptor implements Iterable<T> {

		private final ResettableIterator<? extends T> proxiedIterator;

		/**
		 * Set to true (default) the interceptor will work on the proxiedIterator, if set to false it will ignore any method invocations.
		 */
		protected boolean enabled = true;

		/**
		 * Creates a proxy that wraps the given Iterator in order to seamlessly iterate on them by exposing the API of a single object
		 * @param proxiedIterator The Iterator to be proxied
		 */
		protected ProxyIterator(ResettableIterator<? extends T> proxiedIterator) {
			this.proxiedIterator = proxiedIterator;
		}

		/**
		 * {@inheritDoc}
		 */
		public Object invoke(Object obj, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
			if (method.getName().equals("iterator")) return iterator();
			if (enabled) return createProxyIterator(iterateOnValues(method, args), (Class<Object>)method.getReturnType());
			return null;
		}

		/**
		 * Invokes the given method with the given arguments on all the object in the iterator wrapped by this proxy
		 * @param method The method to be invoked
		 * @param args The arguments used to invoke the given method
		 * @return An Iterator over the results on all the invoctions of the given method
		 * @throws InvocationTargetException
		 * @throws IllegalAccessException
		 */
		protected ResettableIterator<Object> iterateOnValues(Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
			if (method.getName().equals("finalize")) return null;
			method.setAccessible(true);
			proxiedIterator.reset();
			List<Object> list = new LinkedList<Object>();
			while (proxiedIterator.hasNext()) { list.add(method.invoke(proxiedIterator.next(), args)); }
			return new ResettableIteratorOnIterable(list);
		}

		/**
		 * Creates a ProxyIterator of the given class that wraps the given Iterator
		 * @param proxiedIterator The Iterator to be proxied
		 * @param clazz The class dinamically implemented by the newly created proxy
		 * @return The newly created proxy
		 */
		public static <T> T createProxyIterator(ResettableIterator<? extends T> proxiedIterator, Class<T> clazz) {
			return ProxyUtil.createIterableProxy(new ProxyIterator<T>(proxiedIterator), clazz);
		}

		/**
		 * Creates a ProxyIterator of the same class of the given item that wraps the given Iterator
		 * @param proxiedIterator The Iterator to be proxied
		 * @param firstItem An instance of the class dinamically implemented by the newly created proxy
		 * @return The newly created proxy
		 */
		public static <T> T createProxyIterator(ResettableIterator<? extends T> proxiedIterator, T firstItem) {
			T proxy = createProxyIterator(proxiedIterator, (Class<T>)firstItem.getClass());
			proxiedIterator.reset();
			return proxy;
		}

		/**
		 * {@inheritDoc}
		 */
		@SuppressWarnings("unchecked")
		public Iterator<T> iterator() {
			return (Iterator<T>)proxiedIterator;
		}
	}

	final static class ClassImposterizer  {

		static final ClassImposterizer INSTANCE = new ClassImposterizer();

		private ClassImposterizer() {}

		private final Objenesis objenesis = new ObjenesisStd();

		private static final NamingPolicy DEFAULT_POLICY = new DefaultNamingPolicy() {
			/**
			 * {@inheritDoc}
			 */
//			@Override
			protected String getTag() {
				return "ByLambdajWithCGLIB";
			}
		};

		private static final NamingPolicy SIGNED_CLASSES_POLICY = new DefaultNamingPolicy() {
			/**
			 * {@inheritDoc}
			 */
			@Override
			public String getClassName(String prefix, String source, Object key, Predicate names) {
				return "codegen." + super.getClassName(prefix, source, key, names);
			}

			/**
			 * {@inheritDoc}
			 */
//			@Override
			protected String getTag() {
				return "ByLambdajWithCGLIB";
			}
		};

		private static final CallbackFilter IGNORE_BRIDGE_METHODS = new CallbackFilter() {
			public int accept(Method method) {
				return method.isBridge() ? 1 : 0;
			}
		};

		<T> T imposterise(Callback callback, Class<T> mockedType, Class<?>... ancillaryTypes) {
			setConstructorsAccessible(mockedType, true);
			Class<?> proxyClass = createProxyClass(mockedType, ancillaryTypes);
			return mockedType.cast(createProxy(proxyClass, callback));
		}

		private void setConstructorsAccessible(Class<?> mockedType, boolean accessible) {
			for (Constructor<?> constructor : mockedType.getDeclaredConstructors()) {
				constructor.setAccessible(accessible);
			}
		}

		private Class<?> createProxyClass(Class<?> mockedType, Class<?>...interfaces) {
			if (mockedType == Object.class) mockedType = ClassWithSuperclassToWorkAroundCglibBug.class;

			Enhancer enhancer = new ClassEnhancer();
			enhancer.setUseFactory(true);
			enhancer.setSuperclass(mockedType);
			enhancer.setInterfaces(interfaces);

			enhancer.setCallbackTypes(new Class[]{MethodInterceptor.class, NoOp.class});
			enhancer.setCallbackFilter(IGNORE_BRIDGE_METHODS);
			enhancer.setNamingPolicy(mockedType.getSigners() != null ? SIGNED_CLASSES_POLICY : DEFAULT_POLICY);

			return enhancer.createClass();
		}

		private static class ClassEnhancer extends Enhancer {
			/**
			 * {@inheritDoc}
			 */
			@Override
			protected void filterConstructors(Class sc, List constructors) { }
		}

		private Object createProxy(Class<?> proxyClass, Callback callback) {
			Factory proxy = (Factory) objenesis.newInstance(proxyClass);
			proxy.setCallbacks(new Callback[] {callback, NoOp.INSTANCE});
			return proxy;
		}

		/**
		 * Class With Superclass To WorkAround Cglib Bug
		 */
		public static class ClassWithSuperclassToWorkAroundCglibBug {}
	}

	public static class Argument<T> {

		private final InvocationSequence invocationSequence;

		Argument(InvocationSequence invocationSequence) {
			this.invocationSequence = invocationSequence;
		}

		/**
		 * The JavaBean compatible names of the properties defined by the invocations sequence of this Argument.
		 * For example on an Argument defined as <code>on(Person.class).getBestFriend().isMale()</code> it returns "bestFriend.male"
		 * @return The names of the properties defined by the invocations sequence of this Argument
		 */
		public String getInkvokedPropertyName() {
			return invocationSequence.getInkvokedPropertyName();
		}

		/**
		 * Evaluates this Argument on the given object
		 * @param object The Object on which this Argument should be evaluated. It must be compatible with the Argument's root class.
		 * @return The value of this Argument for the given Object
		 */
		@SuppressWarnings("unchecked")
		public T evaluate(Object object) {
			return (T)invocationSequence.invokeOn(object);
		}

		/**
		 * Returns the root class from which the sequence of method invocation defined by this argument starts
		 */
		public Class<?> getRootArgumentClass() {
			return invocationSequence.getRootInvokedClass();
		}

		/**
		 * Returns the type returned by the last method of the invocations sequence represented by this Argument.
		 */
		@SuppressWarnings("unchecked")
		public Class<T> getReturnType() {
			return (Class<T>)invocationSequence.getReturnType();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String toString() {
			return invocationSequence.toString();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean equals(Object object) {
			return object instanceof Argument<?> && invocationSequence.equals(((Argument<?>)object).invocationSequence);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public int hashCode() {
			return invocationSequence.hashCode();
		}
	}

	public static final class IntrospectionUtil {

		private IntrospectionUtil() {}

		/**
		 * Returns the bean compliant name of the property accessed by the given method
		 * @param invokedMethod The method to be introspected
		 * @return The bean compliant name of the property accessed by the given method
		 */
		public static String getPropertyName(Method invokedMethod) {
			String methodName = invokedMethod.getName();
			if ((methodName.startsWith("get") || methodName.startsWith("set")) && methodName.length() > 3) methodName = methodName.substring(3);
			else if (methodName.startsWith("is") && methodName.length() > 2) methodName = methodName.substring(2);
			return methodName.substring(0, 1).toLowerCase(Locale.getDefault()) + methodName.substring(1);
		}

		/**
		 * Return the value of named property on the given bean
		 * @param bean The bean to be introspected
		 * @param propertyName The name of the property to be introspected
		 * @return The value of named property on the given bean
		 */
		public static Object getPropertyValue(Object bean, String propertyName) {
			if (bean == null) return null;
			int dotPos = propertyName.indexOf('.');
			if (dotPos > 0) return getPropertyValue(getPropertyValue(bean, propertyName.substring(0, dotPos)), propertyName.substring(dotPos + 1));

			String accessorName = propertyName.substring(0, 1).toUpperCase(Locale.getDefault()) + propertyName.substring(1);
			try {
				return bean.getClass().getMethod("get" + accessorName).invoke(bean, (Object[]) null);
			} catch (Exception e) {
				return getBooleanPropertyValue(bean, propertyName, accessorName);
			}
		}

		private static Object getBooleanPropertyValue(Object bean, String propertyName, String accessorName) {
			try {
				return bean.getClass().getMethod("is" + accessorName).invoke(bean, (Object[]) null);
			} catch (Exception e) {
				return getPlainPropertyValue(bean, propertyName);
			}
		}

		private static Object getPlainPropertyValue(Object bean, String propertyName) {
			try {
				return bean.getClass().getMethod(propertyName).invoke(bean, (Object[]) null);
			} catch (Exception e) {
				throw new RuntimeException();
//				throw new IntrospectionException(e);
			}
		}

		/**
		 * Finds the consructor of the given class that matches the given arguments
		 * @param clazz The class for which a constructor should be found
		 * @param args The arguments that have to be assignable to the parameters of the constructor to be found
		 * @return The consructor of the given class that matches the given arguments
		 */
		public static <T> Constructor<T> findConstructor(Class<T> clazz, Object... args) {
			return findConstructor(clazz, objectsToClasses(args));
		}

		/**
		 * Finds the consructor of the given class having arguments of the given types
		 * @param clazz The class for which a constructor should be found
		 * @param parameterTypes The types of the parameters of the constructor to be found
		 * @return The consructor of the given class having arguments of the given types
		 */
		public static <T> Constructor<T> findConstructor(Class<T> clazz, Class<?>... parameterTypes) {
			try {
				return clazz.getConstructor(parameterTypes);
			} catch (NoSuchMethodException e) {
				Constructor<T> constructor = discoverConstructor(clazz, parameterTypes);
				if (constructor == null) {
					throw new RuntimeException();
//					throw new IntrospectionException(e);
				}
				return constructor;
			}
		}

		private static <T> Constructor<T> discoverConstructor(Class<?> clazz, Class<?>... parameterTypes) {
			for (Constructor<?> c : clazz.getConstructors()) {
				if (areCompatible(c.getParameterTypes(), parameterTypes)) return (Constructor<T>)c;
			}
			return null;
		}


		/**
		 * Finds a method of the given class with the given name that matches the given arguments
		 * @param clazz The class containing the method should be found
		 * @param args The arguments that have to be assignable to the parameters of the method to be found
		 * @return The method of the given class with the given name that matches the given arguments
		 */
		public static Method findMethod(Class<?> clazz, String methodName, Object... args) {
			return findMethod(clazz, methodName, objectsToClasses(args));
		}

		/**
		 * Finds a method of the given class with the given name having arguments of the given types
		 * @param clazz The class containing the method should be found
		 * @param parameterTypes The types of the parameters of the method to be found
		 * @return The method of the given class with the given name having arguments of the given types
		 */
		public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
			try {
				return clazz.getMethod(methodName,  parameterTypes);
			} catch (NoSuchMethodException e) {
				Method method = discoverMethod(clazz, methodName, parameterTypes);
				if (method == null) {
					throw new RuntimeException();
//					throw new IntrospectionException(e);
				}
				return method;
			}
		}

		private static Method discoverMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && areCompatible(m.getParameterTypes(), parameterTypes)) return m;
			}
			return null;
		}

		private static boolean areCompatible(Class<?>[] methodParams, Class<?>[] actualParam) {
			if (methodParams == null || methodParams.length != actualParam.length) return false;
			for (int i = 0; i < methodParams.length; i++) {
				if (!areCompatible(methodParams[i], actualParam[i])) return false;
			}
			return true;
		}

		private static boolean areCompatible(Class<?> methodParam, Class<?> actualParam) {
			return methodParam.isAssignableFrom(actualParam) ||
				(methodParam.isPrimitive() ? areBoxingCompatible(methodParam, actualParam) : areBoxingCompatible(actualParam, methodParam));
		}

		private static boolean areBoxingCompatible(Class<?> primitiveClass, Class<?> boxedClass) {
			return boxedClass.getSimpleName().toLowerCase(Locale.getDefault()).startsWith(primitiveClass.getSimpleName());
		}

		private static Class<?>[] objectsToClasses(Object... args) {
			Class<?>[] parameterTypes = new Class<?>[args == null ? 0 : args.length];
			for (int i = 0; i < parameterTypes.length; i++) { parameterTypes[i] = args[i].getClass(); }
			return parameterTypes;
		}

		/**
		 * Returns a clone of the given original Object
		 * @param original The Object to be cloned
		 * @return A clone of the original object
		 * @throws CloneNotSupportedException if the original object is not cloneable
		 */
		public static Object clone(Object original) throws CloneNotSupportedException {
			if (!(original instanceof Cloneable)) throw new CloneNotSupportedException();
			try {
				return original.getClass().getMethod("clone").invoke(original);
			} catch (Exception e) {
				throw new CloneNotSupportedException();
			}
		}
	}

	public static abstract class ResettableIterator<T> implements Iterator<T> {

		/**
		 * Resets the cursor of this Iterator to its initial position
		 */
		public abstract void reset();

		/**
		 * {@inheritDoc}
		 */
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	public static class ResettableIteratorOnIterable<T> extends ResettableIterator<T> {

		private final Iterable<T> iterable;

		private Iterator<T> iterator;

		/**
		 * Creates a ResettableIterator that wraps the given Iterable
		 * @param iterable The Iterable to be wrapped
		 */
		public ResettableIteratorOnIterable(Iterable<T> iterable) {
			this.iterable = iterable;
			reset();
		}

		/**
		 * {@inheritDoc}
		 */
		public final void reset() {
			iterator = iterable.iterator();
		}

		/**
		 * {@inheritDoc}
		 */
		public boolean hasNext() {
			return iterator.hasNext();
		}

		/**
		 * {@inheritDoc}
		 */
		public T next() {
			return iterator.next();
		}
	}
}

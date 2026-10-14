import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.BuildersKt;

/** Executes the actual compiled private Recorder and Boundary classes with host
 * stand-ins only for Android Debug and JSONArray argument capture. No DB/device. */
public final class GcRecorderHostCheck {
    private static final String PREFIX = "org.inkweft.data.HistoryDeletionBoundaryDiagnosticsTest$";
    private static final Class<?> RECORDER;
    static {
        try { RECORDER = Class.forName(PREFIX + "Recorder"); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static Method method(Object owner, String prefix) {
        return Arrays.stream(owner.getClass().getDeclaredMethods())
            .filter(method -> method.getName().equals(prefix) || method.getName().startsWith(prefix + "$"))
            .findFirst().map(method -> { method.setAccessible(true); return method; }).orElseThrow();
    }
    private static Object call(Object owner, String prefix, Object... args) {
        try { return method(owner, prefix).invoke(owner, args); }
        catch (InvocationTargetException failure) {
            Throwable original = failure.getCause();
            if (original instanceof RuntimeException) throw (RuntimeException) original;
            if (original instanceof Error) throw (Error) original;
            throw new RuntimeException(original);
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static Object recorder(String database, int author) throws Exception {
        Constructor<?> constructor = RECORDER.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(database, author);
    }
    private static void enabled(Object recorder, boolean enabled) throws Exception {
        Field field = RECORDER.getDeclaredField("enabled"); field.setAccessible(true); field.setBoolean(recorder, enabled);
    }
    @SuppressWarnings("unchecked")
    private static ThreadLocal<Integer> sample(Object diagnostic) throws Exception {
        return (ThreadLocal<Integer>) field(diagnostic, "sample");
    }
    private static int pending(Object recorder) throws Exception { return ((Set<?>) field(recorder, "pending")).size(); }
    private static List<?> events(Object recorder) { return (List<?>) call(recorder, "events"); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void await(Object recorder) throws Exception {
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
            (scope, continuation) -> call(recorder, "awaitWorkers", continuation));
    }
    private static void boundary(Object diagnostic, String action, String layer) {
        call(diagnostic, action, layer, (Function0<Unit>) () -> Unit.INSTANCE);
    }
    private static void syntheticTransaction(Object diagnostic) {
        boundary(diagnostic, "begin", "room"); boundary(diagnostic, "begin", "sqlite");
        boundary(diagnostic, "success", "sqlite"); boundary(diagnostic, "end", "sqlite");
        boundary(diagnostic, "success", "room"); boundary(diagnostic, "end", "room");
    }
    private static void filterAndWait(String database) throws Exception {
        Object recorder = recorder(database, 7);
        Object diagnostic = field(recorder, "diagnostics");
        ThreadLocal<Integer> sample = sample(diagnostic);
        List<Runnable> queue = new ArrayList<>();
        Executor wrapped = (Executor) call(diagnostic, "executor", (Executor) queue::add);
        enabled(recorder, true);
        sample.set(6); wrapped.execute(() -> syntheticTransaction(diagnostic));
        require(pending(recorder) == 1 && events(recorder).isEmpty(), "GC task must be tracked but not recorded");
        sample.set(7); wrapped.execute(() -> syntheticTransaction(diagnostic));
        sample.set(8); wrapped.execute(() -> syntheticTransaction(diagnostic));
        sample.remove();
        require(pending(recorder) == 3 && events(recorder).size() == 1, "All owners tracked; author submit retained");
        queue.get(0).run();
        require(pending(recorder) == 2 && events(recorder).size() == 1, "GC exits without author log pollution");
        queue.get(1).run();
        require(pending(recorder) == 1 && events(recorder).size() == 15, "All author transaction endpoints retained");
        for (Object event : events(recorder)) require(((Integer) field(event, "owner")) == 7, "Only author values recorded");
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> waiting = waiter.submit(() -> {
            try { BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> {
                entered.countDown(); return call(recorder, "awaitWorkers", continuation);
            }); } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try {
            require(entered.await(2, TimeUnit.SECONDS), "Host await coroutine must start");
            try { waiting.get(50, TimeUnit.MILLISECONDS); throw new AssertionError("Unlogged finish task was ignored"); }
            catch (TimeoutException expected) { /* Host correctness gate only, not a performance sample. */ }
            queue.get(2).run(); waiting.get(2, TimeUnit.SECONDS);
            require(pending(recorder) == 0 && events(recorder).size() == 15, "Finish must exit before await completes");
            call(recorder, "validate");
            enabled(recorder, false); sample.remove();
            wrapped.execute(() -> {});
            require(pending(recorder) == 1, "Disabled/background setup task still tracked");
            queue.get(3).run(); await(recorder);
            require(events(recorder).size() == 15, "Disabled task must not add events");
        } finally { waiting.cancel(true); waiter.shutdownNow(); require(waiter.awaitTermination(2, TimeUnit.SECONDS), "Host waiter exits"); }
    }
    private static void overflowStillDrains() throws Exception {
        Object recorder = recorder("collector", 7); Object diagnostic = field(recorder, "diagnostics");
        sample(diagnostic).set(7); enabled(recorder, true);
        Executor wrapped = (Executor) call(diagnostic, "executor", (Executor) Runnable::run);
        for (int index = 0; index < 171; index++) wrapped.execute(() -> {});
        await(recorder);
        require(pending(recorder) == 0 && events(recorder).size() == 512, "512 capacity retained and all tasks drain");
        require(((AtomicInteger) field(recorder, "dropped")).get() == 1, "Overflow retained as explicit error count");
        try { call(recorder, "validate"); throw new AssertionError("Overflow was accepted"); }
        catch (AssertionError expected) { require(expected.getMessage() != null && !expected.getMessage().equals("Overflow was accepted"), "Actual overflow assertion required"); }
    }
    private static void exactTuple(Object[] expected) throws Exception {
        Class<?> type = Class.forName(PREFIX + "Boundary");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, long.class,
            long.class, int.class, boolean.class, String.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(expected);
        org.json.JSONArray argument = (org.json.JSONArray) call(instance, "json");
        require(argument.values.equals(Arrays.asList(expected)), "Every compiled tuple value and JVM type must remain exact");
    }
    public static void main(String[] args) throws Exception {
        filterAndWait("collector"); filterAndWait("author"); overflowStillDrains();
        exactTuple(new Object[]{"sqlite-success-enter",88,Long.MAX_VALUE,Long.MIN_VALUE,Integer.MAX_VALUE,
            false,"returned",Long.MAX_VALUE-3,Long.MIN_VALUE+3,17L});
        exactTuple(new Object[]{"room-end-exit",1,0L,1L,1,true,"threw",0L,0L,Long.MAX_VALUE});
        System.out.println("{\"status\":\"PASS_COMPILED_RECORDER_HOST_CHECK_ONLY\",\"scenarios\":5,\"background_tasks_unlogged_but_awaited\":true,\"capacity\":512,\"overflow_rejected_after_real_drain\":true,\"tuple_values_and_types_exact\":true,\"android_json_runtime_tested\":false,\"android_native_execution\":false}");
    }
}

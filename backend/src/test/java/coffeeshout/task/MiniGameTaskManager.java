//package coffeeshout.task;
//
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import lombok.AllArgsConstructor;
//import org.springframework.scheduling.TaskScheduler;
//
//@AllArgsConstructor
//public class MiniGameTaskManager<T> {
//
//    private final Map<T, ChainedTask> tasks = new ConcurrentHashMap<>();
//    private final TaskScheduler scheduler;
//    private ChainedTask lastTask;
//
//    public void addTask(T type, ChainedTask task) {
//        tasks.put(type, task);
//        getLastTask().setNextTask(task);
//    }
//
//    public void startWith(T type) {
//        ChainedTask chainedTask = tasks.get(type);
//        chainedTask.start(scheduler);
//    }
//
//    public void cancel(T type) {
//        ChainedTask chainedTask = tasks.get(type);
//        chainedTask.cancel();
//    }
//
//    private ChainedTask getLastTask() {
//        return tasks.values().stream().toList().getLast();
//    }
//}

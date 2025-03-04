package nigloo.gallerymanager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

import javafx.application.Platform;

public class AsyncPools
{
	public static final Executor FX_APPLICATION = Platform::runLater;
	public static final Executor DISK_IO = ForkJoinPool.commonPool();
	public static final Executor HTTP_REQUEST = ForkJoinPool.commonPool();
	public static final Executor SCRIPT_EXECUTION = ForkJoinPool.commonPool();
	public static final ScheduledExecutorService SCHEDULED_TASK = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "Task-Scheduler");
		thread.setDaemon(true);
		return thread;
	});
	
	private AsyncPools(){throw new UnsupportedOperationException();}
}

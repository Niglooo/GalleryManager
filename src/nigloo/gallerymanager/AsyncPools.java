package nigloo.gallerymanager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import javafx.application.Platform;

public class AsyncPools
{
	public static final Executor FX_APPLICATION = Platform::runLater;
	public static final Executor DISK_IO = Executors.newVirtualThreadPerTaskExecutor();
	public static final Executor HTTP_REQUEST = ForkJoinPool.commonPool();
	public static final Executor SCRIPT_EXECUTION = ForkJoinPool.commonPool();
	
	private AsyncPools(){throw new UnsupportedOperationException();}
}

package nigloo.gallerymanager;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import javafx.application.Platform;

public class AsyncPools
{
	public static final Executor FX_APPLICATION = Platform::runLater;
	public static final Executor DISK_IO = ForkJoinPool.commonPool();
	public static final Executor HTTP_REQUEST = DISK_IO;
	
	private AsyncPools(){throw new UnsupportedOperationException();}
}

package nigloo.gallerymanager.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future.State;

@Log4j2
@RequiredArgsConstructor
class ScriptAsyncExecutor implements Executor
{
    private final Executor delegate;

    private final Queue<Runnable> queue = new ArrayDeque<>();

    private Thread mainScriptThread = null;

    @Override
    public void execute(Runnable command)
    {
        if (mainScriptThread == null)
        {
            delegate.execute(() -> {
                mainScriptThread = Thread.currentThread();
                command.run();
                mainScriptThread = null;
            });
        }
        else
        {
            queue.offer(command);
        }
    }

    public void join(CompletableFuture<?> cf)
    {
        if (mainScriptThread != Thread.currentThread())
        {
            cf.join();
            return;
        }

        while (cf.state() == State.RUNNING)
        {
            Runnable command;
            if ((command = queue.poll()) != null)
            {
                try {
                    command.run();
                } catch (Exception e) {
                    log.error("Error when running script queue", e);
                }
            }
            else
            {
                Thread.onSpinWait();
            }
        }
        cf.join();
    }
}


package nigloo.gallerymanager.filesystem;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.filesystem.FileSystemElement.Status;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.script.ScriptAPI.APIFileSystemElement;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future.State;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class FileSystemService
{
    private static final long USER_REFRESH_MAX_INTERVAL_MS = 100;

    @Inject
    private Gallery gallery;

    @Getter
    private final FileSystemElement root;

    private final boolean keepEmptyFolder;

    // Any ready/write must be done in synchronized(scheduledRefreshUserTasks)
    private final ArrayDeque<FileSystemCommand<?>> scheduledCommands = new ArrayDeque<>();
    private FileSystemCommand<?> runningCommand = null;

    public FileSystemService(boolean keepEmptyFolder) throws IOException
    {
        Injector.init(this);
        this.keepEmptyFolder = keepEmptyFolder;
        root = FileSystemElement.ofDirectory(null,
                                             gallery.getRootFolder(),
                                             Status.NOT_LOADED,
                                             Files.readAttributes(gallery.getRootFolder(), BasicFileAttributes.class));

        AsyncPools.SCHEDULED_TASK.scheduleAtFixedRate(
                this::tryRunNextTask,
                USER_REFRESH_MAX_INTERVAL_MS,
                USER_REFRESH_MAX_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

//TODO Make all commands cancellable. If running new command. Cancel previous one
    @RequiredArgsConstructor
    private abstract class FileSystemCommand<T>
    {
        protected CompletableFuture<T> actualTask = null;
        protected final CompletableFuture<T> userTask = new CompletableFuture<>();
        private final boolean cancellable;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final String errorMessage;

        protected abstract CompletableFuture<T> buildActualTask(BooleanSupplier isCancelled);

        public void start() {
            log.debug("Starting {}", this);
            actualTask = buildActualTask(cancelled::get);
            actualTask.whenCompleteAsync((result, error) -> {
                        tryRunNextTask();

                        if (error != null) {
                            userTask.completeExceptionally(error);
                            log.error("Completing {} with error", this, error);
                            AsyncPools.FX_APPLICATION.execute(new ExceptionDialog(error, errorMessage)::show);
                        } else {
                            log.debug("Completing {}", this);
                            userTask.complete(result);
                        }
                    }, AsyncPools.SCHEDULED_TASK);
        }

        public void cancel() {
            if (cancellable) {
                if (cancelled.compareAndSet(false, true)) {
                    log.debug("Cancelling {}", this);
                }
            }
        }
    }

    private <T> CompletableFuture<T> scheduleCommand(FileSystemCommand<T> fileSystemCommand) {
        synchronized (scheduledCommands) {
            // When scheduling a new command, cancel any cancellable tasks queued
            if (runningCommand != null) {
                runningCommand.cancel();
            }
            var it = scheduledCommands.iterator();
            while (it.hasNext()) {
                FileSystemCommand<?> nextTask = it.next();
                if (nextTask.cancellable) {
                    nextTask.cancel();
                    it.remove();
                }
            }

            scheduledCommands.addLast(fileSystemCommand);
            tryRunNextTask();
        }
        return fileSystemCommand.userTask;
    }

    private void tryRunNextTask()
    {
        synchronized (scheduledCommands)
        {
            if (runningCommand != null && runningCommand.userTask.state() == State.RUNNING) {
                return;
            }

            FileSystemCommand<?> nextTask = scheduledCommands.pollFirst();
            if (nextTask == null) {
                runningCommand = null;
            }
            else {
                nextTask.start();
                runningCommand = nextTask;
            }
        }
    }

    public CompletableFuture<Void> refresh(Collection<Path> paths, boolean deep)
    {
        return scheduleCommand(new RefreshPathsCommand(paths, deep));
    }

    private class RefreshPathsCommand extends FileSystemCommand<Void>
    {
        private final List<Path> paths;
        private final boolean deep;

        public RefreshPathsCommand(Collection<Path> paths, boolean deep)
        {
            super(true, "Error when refreshing paths");

            assert paths != null;
            assert paths.stream().allMatch(Path::isAbsolute);
            assert paths.stream().allMatch(p -> p.startsWith(gallery.getRootFolder()));

            this.paths = paths.stream().distinct().toList();
            this.deep = deep;
        }

        @Override
        protected CompletableFuture<Void> buildActualTask(BooleanSupplier isCancelled)
        {
            return CompletableFuture
                    .allOf(paths.stream()
                                .map(path ->
                                             CompletableFuture
                                                     .supplyAsync(
                                                             () -> internalRefresh(path,
                                                                                   null,
                                                                                   null,
                                                                                   null,
                                                                                   deep ? Integer.MAX_VALUE : 1,
                                                                                   paths.stream()
                                                                                        .filter(p -> p.startsWith(path))
                                                                                        .filter(p -> !p.equals(path))
                                                                                        .collect(Collectors.toSet()),
                                                                                   null,
                                                                                   isCancelled),
                                                             AsyncPools.DISK_IO)
                                                     .thenCompose(f -> f)
                                                     .thenAccept(element -> {
                                                         if (element != null)
                                                         {
                                                             updateFolderStatus(element.parent, true);
                                                         }
                                                     }))
                                .toArray(CompletableFuture[]::new));
        }

        @Override
        public String toString()
        {
            return "RefreshPathsCommand[paths=%s, deep=%s]".formatted(
                    paths.stream().map(gallery::toRelativePath).toList(),
                    deep
            );
        }
    }

    /**
     * TODO doc refresh(List<Image> images)
     * @param images
     * @return
     */
    public CompletableFuture<List<Image>> refresh(List<Image> images)
    {
        return scheduleCommand(new RefreshImagesCommand(images));
    }

    private class RefreshImagesCommand extends FileSystemCommand<List<Image>>
    {
        private final List<Image> images;

        public RefreshImagesCommand(List<Image> images)
        {
            super(true, "Error when refreshing subImages");

            assert images != null;
            assert gallery.getImages(true).containsAll(images);

            this.images = images;
        }

        @Override
        protected CompletableFuture<List<Image>> buildActualTask(BooleanSupplier isCancelled)
        {
            return internalRefresh(root.getPath(), getRoot(), null, null, Integer.MAX_VALUE, Set.of(), images, isCancelled)
                    .thenApply(element -> images);
        }

        @Override
        public String toString()
        {
            String longestCommonPrefix = "";
            if (!images.isEmpty()) {
                List<String> paths = images
                        .stream()
                        .map(Image::getPath)
                        .sorted()
                        .map(Path::toString)
                        .toList();
                String first = paths.getFirst();
                String last = paths.getLast();
                var sb = new StringBuilder();
                for(int i = 0; i < first.length() ; i++) {
                    if(first.charAt(i) != last.charAt(i)) {
                        break;
                    }
                    sb.append(first.charAt(i));
                }
                if (images.size() > 1) {
                    sb.append("...");
                }

                longestCommonPrefix = sb.toString();
            }

            return "RefreshImagesCommand[images=%s images (%s)]".formatted(
                    images.size(),
                    longestCommonPrefix
            );
        }
    }

    /**
     * TODO refresh doc
     *
     * If imagesToRefresh is not null, refresh all images passed, that is ensure all images exists with the right status
     * aNd if not, refresh their parents recursively.s
     *
     * @param path Path to refresh (must match the element if presnet)
     * @param element Element to refresh
     * @param parentElement Parent of the element to refresh
     * @param fileAttributes FileAttributes of the element to refresh
     * @param depth Max depth (0 : refresh only this element ; 1 : refresh direct children ; etc.)
     * @param excludedPaths Paths to exclude from the refresh
     * @param imagesToRefresh images to refresh or null
     * @return The refreshed element
     */
    //TODO use isCancelled
    private CompletableFuture<FileSystemElement> internalRefresh(
            final Path path,
            FileSystemElement element,
            final FileSystemElement parentElement,
            BasicFileAttributes fileAttributes,
            int depth,
            Set<Path> excludedPaths,
            final List<Image> imagesToRefresh,
            final BooleanSupplier isCancelled)
    {
        assert element == null || element.getPath().equals(path);
        assert imagesToRefresh == null || imagesToRefresh.stream().allMatch(i -> i.getAbsolutePath().startsWith(path));

        if (excludedPaths.contains(path)) {
            return CompletableFuture.completedFuture(element);
        }

        if (imagesToRefresh != null && element != null) {
            // No refresh if match
            Image matchingImage = imagesToRefresh.stream().filter(image -> image.getAbsolutePath().equals(path)).findFirst().orElse(null);
            if (matchingImage != null && element.isImage() && imagesToRefresh.size() == 1) {
                log.trace("Skip refreshing image {}", path);
                return CompletableFuture.completedFuture(element);
            }

            Map<Path, ArrayList<Image>> imagesBySubDir = imagesToRefresh
                    .stream()
                    .filter(image ->  image != matchingImage)
                    .collect(Collectors.groupingBy(
                            image ->  path.resolve(image.getAbsolutePath().getName(path.getNameCount())),
                            Collectors.toCollection(ArrayList::new)));


            record ParamSubRefresh(Path path, FileSystemElement element, List<Image> imagesToRefresh) {}
            List<ParamSubRefresh> paramSubRefreshes = new ArrayList<>();
            boolean mismatch = false;
            // Detect mismatches (all images should have they corresponding element by an image and their parents be directories)
            for (Entry<Path, ArrayList<Image>> entry : imagesBySubDir.entrySet()) {
                Path subPath = entry.getKey();
                ArrayList<Image> images = entry.getValue();
                FileSystemElement subElement = element.children.get(subPath);

                if (subElement == null) {
                    mismatch = true;
                    break;
                }

                // Image matching the current subpath
                Image image = images.stream().filter(i -> i.getAbsolutePath().equals(subPath)).findFirst().orElse(null);
                if (image != null) {
                    if (!subElement.isImage()) {
                        mismatch = true;
                        break;
                    }
                    // Remove it for subsequent directory check
                    images.remove(image);
                }
                if (!images.isEmpty()) {
                    if (!subElement.isDirectory()) {
                        mismatch = true;
                        break;
                    }
                    if (image != null) {
                        images.add(image);
                    }
                    paramSubRefreshes.add(new ParamSubRefresh(subPath, subElement, images));
                }
            }

            if (!mismatch) {
                log.trace("Skip refreshing directory {}", path);
                FileSystemElement fElement = element;
                return Utils.observe(
                        CompletableFuture.allOf(paramSubRefreshes
                            .stream()
                            .map(p -> CompletableFuture.supplyAsync(
                                    () ->internalRefresh(p.path, p.element, fElement, null, depth - 1, excludedPaths, p.imagesToRefresh, isCancelled),
                                    AsyncPools.DISK_IO).thenCompose(f -> f))
                            .toArray(CompletableFuture[]::new)).thenApply(v -> fElement),
                        (v, error) -> {
                            if (!keepEmptyFolder) {
                                fElement.children.values().removeIf(childElement -> childElement.getStatusDirectory() == Status.EMPTY);
                            }
                            updateFolderStatus(fElement, false);
                        });
            }
        }

        if (fileAttributes == null)
        {
            if (isCancelled.getAsBoolean())
            {
                return CompletableFuture.completedFuture(null);
            }

            try
            {
                fileAttributes = Files.readAttributes(path, BasicFileAttributes.class);
            }
            catch (NoSuchFileException ignored)
            {
            }
            catch (Exception e)
            {
                return CompletableFuture.failedFuture(e);
            }
        }

        // N'existe pas sur le disque
        if (fileAttributes == null)
        {
            Collection<Image> images = gallery.findImagesIn(path, false);

            // N'existe pas dans la gallery
            if (images.isEmpty())
            {
                if (element == null) {
                    element = findElement(parentElement, path, false);
                }
                if (element != null) {
                    element.removeFromParent();
                }
            }
            else
            {
                createUpdateAllDeleted(path, element, parentElement, images);
            }
        }
        else if (fileAttributes.isRegularFile())
        {
            Collection<Image> images = gallery.findImagesIn(path, false);
            if (Image.isImage(path))
            {
                element = createUpdateAllDeleted(path, element, parentElement, images);
                Image image = gallery.getImage(path);
                element.setImage(image, image.isSaved() ? Status.SYNC : Status.UNSYNC, fileAttributes);
            }
            else {
                if (!images.isEmpty()) {
                    element = createUpdateAllDeleted(path, element, parentElement, images);
                }
            }
            return CompletableFuture.completedFuture(element);
        }
        else if (fileAttributes.isDirectory())
        {
            if (depth <= 0 || (imagesToRefresh != null && imagesToRefresh.isEmpty())) {
                if (element == null) {
                    element = findElement(parentElement, path, true);
                }
                Status newStatus = List.of(Status.DONT_EXIST, Status.DELETED).contains(element.getStatusDirectory()) ? Status.NOT_LOADED : element.getStatusDirectory();
                element.setDirectory(newStatus, fileAttributes);
                return CompletableFuture.completedFuture(element);
            }
            if (isCancelled.getAsBoolean()) {
                if (element != null) {
                    element.setDirectory(element.getStatusDirectory().isFullyLoaded() ? element.getStatusDirectory() : Status.NOT_FULLY_LOADED, fileAttributes);
                }
                return CompletableFuture.completedFuture(element);
            }

            if (element == null) {
                element = findElement(parentElement, path, true);
            }
            element.setDirectory(Status.LOADING, fileAttributes);

            HashMap<Path, BasicFileAttributes> childrenOnDisk = new HashMap<>();
            try (Stream<Path> list = Files.list(path)) {
                for (Path child : list.toList()) {
                    if (isCancelled.getAsBoolean()) {
                        element.setDirectory(element.getStatusDirectory().isFullyLoaded() ? element.getStatusDirectory() : Status.NOT_FULLY_LOADED, fileAttributes);
                        return CompletableFuture.completedFuture(element);
                    }
                    BasicFileAttributes childAttributes = Files.readAttributes(child, BasicFileAttributes.class);
                    childrenOnDisk.put(child, childAttributes);
                }
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }

            ArrayList<Path> pathImagesIn = gallery.findImagesIn(path, false)
                                                  .stream()
                                                  .map(Image::getAbsolutePath)
                                                  .collect(Collectors.toCollection(ArrayList::new));

            //Remove elements not on disk AND without any (deleted) subImages
            element.children.keySet().removeIf((Path pathChildElement) ->
                                                       !childrenOnDisk.containsKey(pathChildElement) &&
                                                               pathImagesIn.stream().noneMatch(pathChildElement::startsWith));


            record SubRefreshParam(Path path, FileSystemElement element, BasicFileAttributes fileAttributes, List<Image> imagesToRefresh) {}

            // Refresh element on disk
            ArrayList<SubRefreshParam> subRefresh = new ArrayList<>();
            final FileSystemElement fElement = element;
            childrenOnDisk.forEach((childPath, childFA) -> subRefresh.add(
                    new SubRefreshParam(childPath, fElement.children.get(childPath), childFA, imagesToRefresh != null ? new ArrayList<>() : null)));
            if (imagesToRefresh != null) {
                for (Image imageToRefresh : imagesToRefresh) {
                    for (SubRefreshParam p : subRefresh) {
                        if (imageToRefresh.getAbsolutePath().startsWith(p.path)) {
                            p.imagesToRefresh.add(imageToRefresh);
                        }
                    }
                }
            }

            // Refresh deleted subImages
            int nameCount = path.getNameCount();
            List<Path> deletedPaths = pathImagesIn.stream()
                                                  .filter(p -> p.getNameCount() > nameCount)
                                                  .map(p -> path.resolve(p.getName(nameCount)))
                                                  .distinct()
                                                  .filter(p -> !childrenOnDisk.containsKey(p))
                                                  .toList();
            for (Path deletedPath : deletedPaths) {
                subRefresh.add(new SubRefreshParam(deletedPath, null, null, null));
            }

            return Utils.observe(
                    CompletableFuture.allOf(subRefresh.stream()
                                                      .map(p -> CompletableFuture.supplyAsync(
                                                              () -> internalRefresh(p.path, p.element, fElement, p.fileAttributes, depth - 1, excludedPaths, p.imagesToRefresh, isCancelled),
                                                              AsyncPools.DISK_IO).thenCompose(f -> f))
                                                      .toArray(CompletableFuture[]::new)).thenApply(v -> fElement),
                    (v, error) -> {
                        if (!keepEmptyFolder) {
                            fElement.children.values().removeIf(childElement -> childElement.getStatusDirectory() == Status.EMPTY);
                        }
                        updateFolderStatus(fElement, false);
                    });
        }

        return CompletableFuture.completedFuture(null);
    }

    private FileSystemElement createUpdateAllDeleted(Path pathElement, FileSystemElement element, FileSystemElement parentElement, Collection<Image> imagesInElement)
    {
        if (element == null) {
            element = findElement(parentElement, pathElement, true);
        }

        boolean directoryDeleted = false;
        Image imageDeleted = null;
        for (Image image : imagesInElement) {
            if (image.getAbsolutePath().equals(pathElement)) {
                imageDeleted = image;
                if (directoryDeleted) {
                    break;
                }
            }
            else {
                directoryDeleted = true;
                if (imageDeleted != null) {
                    break;
                }
            }
        }
        element.setDeleted(imageDeleted, directoryDeleted);

        Map<Path, FileSystemElement> created = new HashMap<>();
        created.put(pathElement, element);

        for (Image image : imagesInElement) {
            Path imagePath = image.getAbsolutePath();

            ArrayList<Path> parentsPaths = new ArrayList<>();
            parentsPaths.add(imagePath);
            while (!parentsPaths.getLast().equals(pathElement)) {
                parentsPaths.add(parentsPaths.getLast().getParent());
            }
            parentsPaths.removeLast();

            Path parentPath = pathElement;
            for (Path path : parentsPaths.reversed()) {
                final Path finalParentPath = parentPath;
                created.computeIfAbsent(path, p -> {
                    FileSystemElement localParentElement = created.get(finalParentPath);
                    if (localParentElement.getStatusDirectory() == Status.DONT_EXIST) {
                        localParentElement.setDirectory(Status.DELETED, null);
                    }
                    if (path.equals(imagePath)) {
                        return FileSystemElement.ofImage(localParentElement, image, Status.DELETED, null);
                    }
                    else {
                        return FileSystemElement.ofDirectory(localParentElement, path, Status.DELETED, null);
                    }
                });
                parentPath = path;
            }
        }

        return element;
    }

    public CompletableFuture<Void> synchronize(Collection<Path> paths, boolean deep)
    {
        return scheduleCommand(new SynchronizePathsCommand(paths, deep));
    }

    private class SynchronizePathsCommand extends FileSystemCommand<Void>
    {
        private final List<Path> paths;
        private final boolean deep;

        public SynchronizePathsCommand(Collection<Path> paths, boolean deep)
        {
            super(true, "Error when synchronizing paths");

            assert paths != null;
            assert paths.stream().allMatch(Path::isAbsolute);
            assert paths.stream().allMatch(p -> p.startsWith(gallery.getRootFolder()));

            this.paths = paths.stream().distinct().toList();
            this.deep = deep;
        }

        @Override
        protected CompletableFuture<Void> buildActualTask(BooleanSupplier isCancelled)//TODO use isCancelled
        {
            return CompletableFuture
                    .allOf(paths.stream()
                                .map(path ->
                                             CompletableFuture
                                                     .supplyAsync(
                                                             () -> internalSynchronize(path,
                                                                                   null,
                                                                                   deep ? Integer.MAX_VALUE : 1,
                                                                                   paths.stream()
                                                                                        .filter(p -> p.startsWith(path))
                                                                                        .filter(p -> !p.equals(path))
                                                                                        .collect(Collectors.toSet())),
                                                             AsyncPools.DISK_IO)
                                                     .thenAccept(element -> {
                                                         if (element != null)
                                                         {
                                                             updateFolderStatus(element.parent, true);
                                                         }
                                                     }))
                                .toArray(CompletableFuture[]::new));
        }

        @Override
        public String toString()
        {
            return "SynchronizePathsCommand[paths=%s, deep=%s]".formatted(
                    paths.stream().map(gallery::toRelativePath).toList(),
                    deep
            );
        }
    }

    private FileSystemElement internalSynchronize(final Path path, FileSystemElement element, int depth, Set<Path> excludedPaths)
    {
        if (excludedPaths.contains(path)) {
            return element;
        }

        if (depth <= 0) {
            return element;
        }

        if (element == null) {
            element = findElement(null, path, false);
        }

        if (element == null) {
            return element;
        }

        if (element.isImage()) {
            Image image = element.getImage();

            switch (element.getStatusImage()) {
                case DELETED:
                    gallery.deleteImages(List.of(image));
                    if (element.isDirectory()) {
                        element.clearImage();
                    }
                    else {
                        element.removeFromParent();
                    }
                    break;

                case UNSYNC:
                    gallery.saveImage(image);
                    element.setImage(image, Status.SYNC, null);
                    break;
            }
        }

        if (element.isDirectory()) {
            element.children.forEach((subPath, subElement) ->
                                             internalSynchronize(subPath, subElement, depth - 1, excludedPaths));
            if (element.getStatusDirectory() == Status.DELETED) {
                if (element.isImage()) {
                    element.clearDirectory();
                }
                else {
                    element.removeFromParent();
                }
            }
            else {
                if (element.children.isEmpty() && !keepEmptyFolder) {
                    element.removeFromParent();
                }
                else {
                    updateFolderStatus(element, false);
                }
            }
        }

        return element;
    }

    public CompletableFuture<Void> delete(Collection<Path> paths, boolean deleteOnDisk)
    {
        return scheduleCommand(new DeletePathsCommand(paths, deleteOnDisk));
    }

    private class DeletePathsCommand extends FileSystemCommand<Void>
    {
        private final Collection<Path> paths;
        private final boolean deleteOnDisk;

        public DeletePathsCommand(Collection<Path> paths, boolean deleteOnDisk)
        {
            super(false, "Error when deleting paths");

            assert paths != null;
            assert paths.stream().allMatch(Path::isAbsolute);
            assert paths.stream().allMatch(p -> p.startsWith(gallery.getRootFolder()));

            this.paths = withoutChildren(paths);
            this.deleteOnDisk = deleteOnDisk;
        }

        @Override
        protected CompletableFuture<Void> buildActualTask(BooleanSupplier isCancelled)//TODO use isCancelled
        {
            return CompletableFuture
                    .allOf(paths.stream()
                                .map(path -> CompletableFuture.supplyAsync(
                                            () -> internalDelete(path,null,deleteOnDisk),
                                            AsyncPools.DISK_IO).thenCompose(f -> f)
                                        .thenAccept(element -> {
                                            if (element != null) {
                                                 element.removeFromParent();
                                                 if (element.parent != null) {
                                                     updateFolderStatus(element.parent, true);
                                                 }
                                             }
                                        }))
                                .toArray(CompletableFuture[]::new));
        }

        @Override
        public String toString()
        {
            return "DeletePathsCommand[paths=%s, deleteOnDisk=%s]".formatted(
                    paths.stream().map(gallery::toRelativePath).toList(),
                    deleteOnDisk
            );
        }
    }

    private CompletableFuture<FileSystemElement> internalDelete(final Path path, FileSystemElement element, final boolean deleteOnDisk) {
        assert element == null || element.getPath().equals(path);

        if (element == null) {
            element = findElement(null, path, false);
        }

        if (element == null) {
            return CompletableFuture.completedFuture(null);
        }
        FileSystemElement fElement = element;

        gallery.setSortOrder(path, null);
        gallery.setSubDirectoriesSortOrder(path, null);

        // Delete image from disk
        if (element.getStatusImage().existsOnDisk() && deleteOnDisk) {
            try {
                Files.delete(path);
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // Delete image from gallery
        Image image = element.getImage();
        if (image != null) {
            gallery.deleteImages(List.of(image));
        }

        if (!element.isDirectory()) {
            return CompletableFuture.completedFuture(element);
        }

        // recursive call to children
        return Utils.observe(
                CompletableFuture.allOf(element.getChildren()
                                                .stream()
                                                .map(childElement -> CompletableFuture.supplyAsync(
                                                        () -> internalDelete(childElement.getPath(), childElement, deleteOnDisk),
                                                        AsyncPools.DISK_IO).thenCompose(f -> f))
                                                .toArray(CompletableFuture[]::new)).thenApply(v -> fElement),
                (v, error) -> {
                    if (!keepEmptyFolder) {
                        fElement.children.values().removeIf(childElement -> childElement.getStatusDirectory() == Status.EMPTY);
                    }
                    updateFolderStatus(fElement, false);
                    if (fElement.getStatusDirectory().existsOnDisk() && deleteOnDisk) {
                        try {
                            FileUtils.forceDelete(path.toFile());
                        }
                        catch (IOException e) {
                             throw new UncheckedIOException(e);
                        }
                    }
                });
    }

    public CompletableFuture<FileSystemElement> move(Path source, Path target)
    {
        return scheduleCommand(new MovePathCommand(source, target));
    }

    private class MovePathCommand extends FileSystemCommand<FileSystemElement>
    {
        private final Path source;
        private final Path target;

        public MovePathCommand(Path source, Path target)
        {
            super(false, "Error when moving " + source + " to " + target);

            assert source != null;
            assert source.isAbsolute();
            assert source.startsWith(gallery.getRootFolder());
            assert target != null;
            assert target.isAbsolute();
            assert target.startsWith(gallery.getRootFolder());

            assert !target.startsWith(source);

            this.source = source;
            this.target = target;
        }

        @Override
        protected CompletableFuture<FileSystemElement> buildActualTask(BooleanSupplier isCancelled)//TODO use isCancelled
        {
            return CompletableFuture.supplyAsync(() -> {

                FileSystemElement sourceElement = findElement(source);
                if (sourceElement == null) {
                    return null;
                }

                FileSystemElement targetElement = findElement(target);
                if (targetElement != null) {
                    if (targetElement.getStatusImage().existsOnDisk() || targetElement.getStatusDirectory().existsOnDisk()) {
                        throw new IllegalStateException(target + " already exists");
                    }
                    targetElement.removeFromParent();
                }

                // Move files on disk
                if (sourceElement.getStatusImage().existsOnDisk() || sourceElement.getStatusDirectory().existsOnDisk()) {
                    try {
                        Files.move(source, target);
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                // Move images in gallery, sort order preference, etc
                gallery.move(source, target);

                // Remove from previous location
                sourceElement.removeFromParent();

                // Move to new location
                FileSystemElement targetParent = findElement(null, target.getParent(), true);
                targetElement = FileSystemElement.ofMoved(sourceElement, targetParent, target);

                return targetElement;

            }, AsyncPools.DISK_IO);
        }

        @Override
        public String toString()
        {
            return "MovePathCommand[source=%s, target=%s]".formatted(
                    gallery.toRelativePath(source),
                    gallery.toRelativePath(target)
            );
        }
    }


    private static void updateFolderStatus(FileSystemElement element, boolean updateParent)
    {
        if (element == null || !element.isDirectory()) {
            return;
        }

        Status currentStatus = element.getStatusDirectory();

        EnumSet<Status> statusFound = EnumSet.noneOf(Status.class);
        for (FileSystemElement child : element.getChildren())
        {
            Status status;
            if (child.getStatusDirectory() == Status.DONT_EXIST) {
                status = child.getStatusImage();
                if (status == Status.DONT_EXIST) {
                    log.warn("Found element ({}) with status {} while updating parent's status", child.getPath(), status);
                }
            }
            else if (child.getStatusImage() == Status.DONT_EXIST) {
                status = child.getStatusDirectory();
            }
            else {
                // 2 statuses for the same path, one of them has to be deleted
                status = child.getStatusImage() != Status.DELETED ? child.getStatusImage() : child.getStatusDirectory();
            }

            statusFound.add(status);
        }

        statusFound.remove(Status.DONT_EXIST); // Shouldn't happen
        statusFound.remove(Status.EMPTY); // Ignore empty folders

        Status newStatus = computeNewStatus(currentStatus, statusFound);

        if (newStatus == currentStatus)
            return;

        element.setStatusDirectory(newStatus);
        if (updateParent && element.parent != null) {
            updateFolderStatus(element.parent, true);
        }
    }

    private static Status computeNewStatus(Status currentStatus, Set<Status> childrenStatuses)
    {
        // If previously was fully loaded, consider it still is
        boolean allFullyLoaded = currentStatus.isFullyLoaded() || childrenStatuses.stream().allMatch(Status::isFullyLoaded);

        if (childrenStatuses.isEmpty())
            return Status.EMPTY;
        else if (childrenStatuses.size() == 1 && childrenStatuses.contains(Status.SYNC) && allFullyLoaded)
            return Status.SYNC;
        else if (childrenStatuses.size() == 1 && childrenStatuses.contains(Status.DELETED) && allFullyLoaded)
            return currentStatus.existsOnDisk() ? Status.UNSYNC : Status.DELETED;
        else if (childrenStatuses.contains(Status.LOADING))
            return Status.LOADING;
        else if ((childrenStatuses.contains(Status.UNSYNC) || childrenStatuses.contains(Status.DELETED)) && allFullyLoaded)
            return Status.UNSYNC;
        else
            return Status.NOT_FULLY_LOADED;
    }

    public List<Image> sort(List<Image> images)
    {
        return internalSort(root.getPath(), root.getChildren(), new ArrayList<>(images)).toList();
    }

    //TODO javadoc with actual comment
    private Stream<Image> internalSort(Path path, Collection<FileSystemElement> elements, ArrayList<Image> images)
    {
        List<SplitFileSystemElement> splitElements = new ArrayList<>(elements.size() * 2);
        for (FileSystemElement element : elements) {
            Path elementPath = element.getPath();

            ArrayList<Image> subImages = new ArrayList<>();
            Iterator<Image> it = images.iterator();
            while (it.hasNext()) {
                Image img = it.next();
                Path imagePath = img.getAbsolutePath();
                if (imagePath.equals(elementPath)) {
                    splitElements.add(new SplitFileSystemElement(element, null));
                    it.remove();
                }
                else if (imagePath.startsWith(elementPath)) {
                    subImages.add(img);
                    it.remove();
                }
            }

            if (!subImages.isEmpty()) {
                splitElements.add(new SplitFileSystemElement(element, subImages));
            }
        }

        splitElements.sort(gallery.getSortOrder(path));

        if (!images.isEmpty()) {
            log.warn("Folder {} was not fully loaded prior to sort ({} unloaded sub subImages)", path, images.size());
        }

        images.stream()
              .map(image -> new SplitFileSystemElement(FileSystemElement.ofImage(null, image, Status.UNSYNC, null), null))
              .forEach(splitElements::add);

        return splitElements.stream().flatMap(splitElement -> {
            if (splitElement.isImage()) {
                return Stream.of(splitElement.getImage());
            }
            else {
                return internalSort(splitElement.getPath(), splitElement.element.getChildren(), splitElement.subImages);
            }
        });
    }

    private record SplitFileSystemElement(FileSystemElement element, ArrayList<Image> subImages) implements APIFileSystemElement
    {
        @Override
        public Path getPath() {
            return element.getPath();
        }

        @Override
        public boolean isImage() {
            return subImages == null;
        }

        @Override
        public boolean isDirectory() {
            return subImages != null;
        }

        @Override
        public Image getImage() {
            return subImages == null ? element.getImage() : null;
        }

        @Override
        public long getLastModified() {
            return element.getLastModified();
        }
    }

    public FileSystemElement findElement(Path path) {
        assert path != null;
        assert path.isAbsolute();
;       assert path.startsWith(gallery.getRootFolder());

        return findElement(null, path, false);
    }

    private FileSystemElement findElement(FileSystemElement from, Path path, boolean createParents) {
        assert path != null;
        assert from == null || path.startsWith(from.getPath());

        if (log.isTraceEnabled()) {
            String type;
            Path p;
            if (from == null) {
                type = "ABS";
                p = path;
            } else {
                type = "REL";
                p = from.getPath().relativize(path);
            }
            log.trace("{} findElement({}, {}})", type, p, createParents);
        }

        if (from == null) {
            from = root;
        }
        Path fromPath = from.getPath();
        if (path.equals(fromPath)) {
            return from;
        }

        /*
         * From : /a
         * Path : /a/b/c/d
         * Parents => [/a/b/c/d, /a/b/c, /a/b]
         */
        ArrayList<Path> parents = new ArrayList<>(path.getNameCount() - fromPath.getNameCount());
        for (Path parent = path ; !parent.equals(fromPath) ; parent = parent.getParent()) {
            parents.add(parent);
        }

        FileSystemElement element = from;
        for (Path parent : parents.reversed()) {
            FileSystemElement child = element.children.get(parent);
            if (child == null) {
                if (!createParents) {
                    return null;
                }

                if (parent.equals(path)) {
                    child = FileSystemElement.ofEmpty(element, parent);
                }
                else {
                    child = FileSystemElement.ofDirectory(element, parent, Status.NOT_FULLY_LOADED, null);
                }
            }
            element = child;
        }

        return element;
    }

    public static Collection<Path> withoutChildren(Collection<Path> paths) {
        return paths.stream().filter(p -> paths.stream().noneMatch(p2 -> !p.equals(p2) && p.startsWith(p2))).toList();
    }

    public void printDebug(PrintStream out) {
        printDebug(out, root,0);
    }

    private void printDebug(PrintStream out, FileSystemElement element, int indentLevel) {
        ArrayList<String> statuses = new ArrayList<>();
        if (element.getStatusDirectory() != Status.DONT_EXIST) {
            statuses.add("DIR="+element.getStatusDirectory());
        }
        if (element.getStatusImage() != Status.DONT_EXIST) {
            statuses.add("FILE="+element.getStatusImage());
        }
        if(statuses.isEmpty()) {
            log.warn("FileSystemElement all DONT_EXIST: "+element.getPath());
        }

        out.print("  ".repeat(indentLevel));
        out.print(element.getPath().getFileName());
        out.print(" ");
        out.print(statuses);
        if (element.getLastModified() > 0) {
            out.print(" "+ Instant.ofEpochMilli(element.getLastModified()).atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (element.getImage() != null) {
            out.print(" img="+element.getImage().getId());
        }
        out.println();

        element.children.entrySet().stream().sorted(Entry.comparingByKey()).map(Entry::getValue).forEach(child -> printDebug(out, child, indentLevel+1));
    }
}

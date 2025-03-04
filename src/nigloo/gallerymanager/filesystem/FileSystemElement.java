package nigloo.gallerymanager.filesystem;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.script.ScriptAPI.APIFileSystemElement;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class FileSystemElement implements APIFileSystemElement
{
	//TODO delete me
	@Deprecated public FileSystemElement(Path rootFolder, Status status){
		path = null;parent=null;
	}
	@Deprecated public FileSystemElement(Image image, Status status){
		path = null;parent=null;
	}
	@Deprecated public FileSystemElement withStatus(Status status) {
		deleteMeStatus = status;
		return this;
	}
	@Deprecated public Status deleteMeStatus;
	@Deprecated public Status getStatus() {
		return deleteMeStatus;
	}



	///////////////////////////////////////////////////////

	@RequiredArgsConstructor
	public enum Status
	{
		NOT_LOADED(false, true),
		LOADING(false, true),
		NOT_FULLY_LOADED(false, true),
		DONT_EXIST(false, false),
		EMPTY(true, true),
		SYNC(true, true),
		UNSYNC(true, true),
		DELETED(true, false);
		
		private final boolean fullyLoaded;
		private final boolean existsOnDisk;
		
		public boolean isFullyLoaded() {
			return fullyLoaded;
		}
		
		public boolean isNotFullyLoaded() {
			return !fullyLoaded;
		}

		public boolean existsOnDisk() {
			return existsOnDisk;
		}
	}
	
	@Getter
	private Image image;
	private final Path path;
	final FileSystemElement parent;
	final ConcurrentHashMap<Path, FileSystemElement> children = new ConcurrentHashMap<>();
	@Getter
	private Status statusImage;
	@Getter
	private Status statusDirectory;
	@Getter
	private long lastModified;

	@Getter
	private volatile long lastUpdate;// update in status is updated

	private static final VarHandle LAST_UPDATE;
	static {
		try {
			MethodHandles.Lookup l = MethodHandles.lookup();
			LAST_UPDATE = l.findVarHandle(FileSystemElement.class, "lastUpdate", long.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private FileSystemElement(Image image, Path path, FileSystemElement parent, Status statusImage, Status statusDirectory) {
		this.image = image;
		this.path = path;
		this.parent = parent;
		this.statusImage = statusImage;
		this.statusDirectory = statusDirectory;
	}

	static FileSystemElement ofEmpty(FileSystemElement parent, Path path)
	{
		assert parent != null && parent.isDirectory();
		assert path != null;

		return addToParent(new FileSystemElement(null, path, parent, Status.DONT_EXIST, Status.DONT_EXIST), parent);
	}

	static FileSystemElement ofMoved(FileSystemElement element, FileSystemElement newParent, Path newPath)
	{
		assert newParent != null && newParent.isDirectory();
		assert newPath != null;
		assert newParent.getPath().equals(newPath.getParent());

		FileSystemElement moved = new FileSystemElement(element.image, newPath, newParent, element.statusImage, element.statusDirectory);
		moved.lastModified = element.lastModified;

		element.children.forEach((childPath, childElement) -> {
			Path newChildPath = newPath.resolve(childPath.getFileName());
			ofMoved(childElement, moved, newChildPath);
		});

		return addToParent(moved, newParent);
	}

	void setDeleted(Image imageDeleted, boolean directoryDeleted) {
		assert imageDeleted == null || imageDeleted.getAbsolutePath().equals(getPath());

		statusImage = imageDeleted != null ? Status.DELETED : Status.DONT_EXIST;
		statusDirectory = directoryDeleted ? Status.DELETED : Status.DONT_EXIST;
		image = imageDeleted;
		children.clear();
		lastModified = -1;

		updateLastUpdateIncludingParents();
	}

	void setImage(Image image, Status statusImage, BasicFileAttributes fileAttributes) {
		assert image != null;
		assert statusImage == Status.SYNC || statusImage == Status.UNSYNC || statusImage == Status.DELETED;
		assert path == null || path.equals(image.getAbsolutePath());
		assert fileAttributes == null || fileAttributes.isRegularFile();

		this.image = image;
		this.statusImage = statusImage;
		if (fileAttributes != null) {
			this.lastModified = fileAttributes.lastModifiedTime().toMillis();
		}
		updateLastUpdateIncludingParents();
	}

	void clearImage() {
		this.image = null;
		this.statusImage = Status.DONT_EXIST;
		updateLastUpdateIncludingParents();
	}

	void setDirectory(Status statusDirectory, BasicFileAttributes fileAttributes) {
		assert statusDirectory != null && statusDirectory != Status.DONT_EXIST;
		assert fileAttributes == null || fileAttributes.isDirectory();

		this.statusDirectory = statusDirectory;
		if (fileAttributes != null) {
			this.lastModified = fileAttributes.lastModifiedTime().toMillis();
		}
		updateLastUpdateIncludingParents();
	}

	void setStatusDirectory(Status statusDirectory) {
		assert statusDirectory != null && statusDirectory != Status.DONT_EXIST;

		this.statusDirectory = statusDirectory;
		updateLastUpdateIncludingParents();
	}

	void clearDirectory() {
		this.children.clear();
		this.statusDirectory = Status.DONT_EXIST;
		updateLastUpdateIncludingParents();
	}

	public static FileSystemElement ofImage(FileSystemElement parent, Image image, Status status, BasicFileAttributes fileAttributes)
	{
		assertIsDirectory(parent);
		Objects.requireNonNull(image, "image");
		Objects.requireNonNull(status, "status");
		if (status != Status.SYNC && status != Status.UNSYNC && status != Status.DELETED)
			throw new IllegalArgumentException("Invalid status. Must be one of " + Status.SYNC + ", " + Status.UNSYNC
			        + ", " + Status.DELETED + ". Got: " + status);

		return addToParent(new FileSystemElement(image, image.getAbsolutePath(), parent, status, Status.DONT_EXIST), parent);
	}
	
	public static FileSystemElement ofDirectory(FileSystemElement parent, Path path, Status status, BasicFileAttributes fileAttributes)
	{
		assertIsDirectory(parent);
		Objects.requireNonNull(path, "path");
		if (!path.isAbsolute())
			throw new IllegalArgumentException("path must be absolute. Got: " + path);
		Objects.requireNonNull(status, "status");
		if (status == Status.DONT_EXIST)
			throw new IllegalArgumentException("Invalid status. Cannot be "+Status.DONT_EXIST);

		return addToParent(new FileSystemElement(null, path, parent, Status.DONT_EXIST, status), parent);
	}

	private static void assertIsDirectory(FileSystemElement parent) {
		if (parent != null && !parent.isDirectory())
			throw new IllegalArgumentException(parent.getPath() + " is not a directory");
	}

	private static FileSystemElement addToParent(FileSystemElement element, FileSystemElement parent) {
		if (parent != null) {
			if (element.getPath().getNameCount() != parent.getPath().getNameCount() + 1 ||
					!element.getPath().startsWith(parent.getPath())) {
				throw new IllegalArgumentException(parent.getPath() + " is not the parent of " + element.getPath());
			}
			parent.children.put(element.getPath(), element);
		}
		element.updateLastUpdateIncludingParents();
		return element;
	}

	private void updateLastUpdateIncludingParents() {
		lastUpdate = System.nanoTime();
		FileSystemElement parent = this.parent;
		while (parent != null) {
			long lastUpdateThis = lastUpdate;
			long lastUpdateParent = parent.lastUpdate;
			if (lastUpdateParent < lastUpdateThis) {
				if (LAST_UPDATE.compareAndSet(parent, lastUpdateParent, lastUpdateThis)) {
					parent = parent.parent;
				}
			}
			else {
				return;
			}
		}
	}

	void removeFromParent() {
		FileSystemElement parent = this.parent;
		if (parent != null) {
			parent.children.remove(getPath());
			parent.updateLastUpdateIncludingParents();
		}
	}

	@Override
	public String toString()
	{
		return getPath().toString();
	}
	
	@Override
	public int hashCode()
	{
		return image != null ? image.hashCode() : path.hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		
		FileSystemElement other = (FileSystemElement) obj;
		return (image != null) ? image.equals(other.image) : path.equals(other.path);
	}
	
	/**
	 * @return The absolute path of the element
	 */
	public Path getPath()
	{
		return image != null ? image.getAbsolutePath() : path;
	}
	
	public boolean isDirectory()
	{
		return statusDirectory != Status.DONT_EXIST;
	}
	
	public boolean isImage()
	{
		return statusImage != Status.DONT_EXIST;
	}

	public Collection<FileSystemElement> getChildren()
	{
		return Collections.unmodifiableCollection(children.values());
	}
}

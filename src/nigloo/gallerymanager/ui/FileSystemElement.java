package nigloo.gallerymanager.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import lombok.Getter;
import lombok.With;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.script.ScriptAPI.APIFileSystemElement;

public class FileSystemElement implements APIFileSystemElement
{
	public enum Status
	{
		NOT_LOADED(false),
		LOADING(false),
		NOT_FULLY_LOADED(false),
		EMPTY(true),
		SYNC(true),
		UNSYNC(true),
		DELETED(true);
		
		private final boolean fullyLoaded;
		
		private Status(boolean fullyLoaded)
		{
			this.fullyLoaded = fullyLoaded;
		}
		
		public boolean isFullyLoaded()
		{
			return fullyLoaded;
		}
		
		public boolean isNotFullyLoaded()
		{
			return !fullyLoaded;
		}
	}
	
	@Getter
	private final Image image;
	private final Path path;
	@Getter @With
	private final Status status;
	@Getter(lazy = true)
	private final long lastModified = computeLastModified();
	
	public FileSystemElement(Image image, Status status)
	{
		this(image, null, status);
		Objects.requireNonNull(image, "image");
		Objects.requireNonNull(status, "status");
		if (status != Status.SYNC && status != Status.UNSYNC && status != Status.DELETED)
			throw new IllegalArgumentException("Invalid status. Must be one of " + Status.SYNC + ", " + Status.UNSYNC
			        + ", " + Status.DELETED + ". Got: " + status);
	}
	
	public FileSystemElement(Path path, Status status)
	{
		this(null, path, status);
		Objects.requireNonNull(path, "path");
		if (!path.isAbsolute())
			throw new IllegalArgumentException("path must be absolute. Got: " + path);
		Objects.requireNonNull(status, "status");
	}
	
	private FileSystemElement(Image image, Path path, Status status)
	{
		this.image = image;
		this.path = path;
		this.status = status;
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
		return image == null;
	}
	
	public boolean isImage()
	{
		return image != null;
	}
	
	private long computeLastModified()
	{
		try
		{
			return Files.getLastModifiedTime(getPath()).toMillis();
		}
		catch (IOException e)
		{
			return 0;
		}
	}
}

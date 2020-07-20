package nigloo.gallerymanager.ui;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import nigloo.gallerymanager.model.Image;

public class FileSystemElement
{
	private static final javafx.scene.image.Image ICON_FOLDER_SYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_UNSYNC;
	private static final javafx.scene.image.Image ICON_FOLDER_DELETED;
	private static final javafx.scene.image.Image ICON_IMAGE_SYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_UNSYNC;
	private static final javafx.scene.image.Image ICON_IMAGE_DELETED;
	static {
		try {
			ICON_FOLDER_SYNC = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/folder_sync.png"));
			ICON_FOLDER_UNSYNC = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/folder_unsync.png"));
			ICON_FOLDER_DELETED = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/folder_deleted.png"));
			ICON_IMAGE_SYNC = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/image_sync.png"));
			ICON_IMAGE_UNSYNC = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/image_unsync.png"));
			ICON_IMAGE_DELETED = new javafx.scene.image.Image(FileSystemElement.class.getModule().getResourceAsStream("resources/images/icons/image_deleted.png"));
		} catch (IOException e) {
			throw new IOError(e);
		}
	}
	
	public enum Status { UNSET, SYNC, UNSYNC, DELETED }

	private final Image image;
	private final Path path;
	private Status status;
	
	public FileSystemElement(Image image, Status status) {
		this.image = Objects.requireNonNull(image, "image");
		this.path = null;
		this.status = Objects.requireNonNull(status, "status");
	}
	
	public FileSystemElement(Path path) {
		this.image = null;
		this.path = Objects.requireNonNull(path, "path");
		this.status = Status.UNSET;
	}
	
	public Path getPath()
	{
		return image != null ? image.getPath() : path;
	}
	
	public boolean isDirectory() {
		return image == null;
	}
	
	@SuppressWarnings("incomplete-switch")
	public javafx.scene.image.Image getIcon()
	{
		if (image != null)
		{
			switch (status) {
			case SYNC:
				return ICON_IMAGE_SYNC;
			case UNSYNC:
				return ICON_IMAGE_UNSYNC;
			case DELETED:
				return ICON_IMAGE_DELETED;
			}
		}
		else
		{
			switch (status) {
			case SYNC:
				return ICON_FOLDER_SYNC;
			case UNSYNC:
				return ICON_FOLDER_UNSYNC;
			case DELETED:
				return ICON_FOLDER_DELETED;
			}
		}
		
		throw new IllegalStateException("status = "+status);
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = Objects.requireNonNull(status, "status");
	}
}

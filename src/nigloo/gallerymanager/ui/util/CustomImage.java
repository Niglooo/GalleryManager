package nigloo.gallerymanager.ui.util;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

public abstract class CustomImage extends WritableImage
{
	protected final ReadOnlyObjectWrapper<Exception> loadingException;
	protected final ReadOnlyDoubleWrapper loadingProgress;
	
	public CustomImage(int width, int height)
	{
		super(width, height);
		
		loadingException = new ReadOnlyObjectWrapper<>(this, "loadingException", null);
		loadingProgress = new ReadOnlyDoubleWrapper(this, "loadingProgress", 0);
	}
	
	public final ReadOnlyObjectProperty<Exception> loadingExceptionProperty()
	{
		return loadingException.getReadOnlyProperty();
	}
	
	public final ReadOnlyDoubleProperty loadingProgressProperty()
	{
		return loadingProgress.getReadOnlyProperty();
	}

	public static boolean isError(Image image)
	{
		if (image instanceof CustomImage customImage)
			return customImage.exceptionProperty().get() != null;

		return image.isError();
	}

	public static double getProgress(Image image)
	{
		if (image instanceof CustomImage customImage)
			return customImage.loadingProgressProperty().get();

		return image.getProgress();
	}

	public static boolean isComplete(Image image)
	{
		return !isError(image) && getProgress(image) >= 1;
	}
}
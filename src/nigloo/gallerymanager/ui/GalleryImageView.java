package nigloo.gallerymanager.ui;

import java.util.Objects;
import java.util.function.Function;

import javafx.scene.image.ImageView;
import nigloo.gallerymanager.model.Image;

public class GalleryImageView extends ImageView
{
	private final Image galleryImage;
	private final Function<Image, javafx.scene.image.Image> getFXImage;
	private boolean displayed;
	
	public GalleryImageView(Image galleryImage, Function<Image, javafx.scene.image.Image> getFXImage)
	{
		super();
		this.galleryImage = Objects.requireNonNull(galleryImage, "galleryImage");
		this.getFXImage = Objects.requireNonNull(getFXImage, "getFXImage");
		this.displayed = false;
	}
	
	public boolean isDisplayed()
	{
		return displayed;
	}
	
	public void setDisplayed(boolean displayed)
	{
		if (this.displayed != displayed)
		{
			this.displayed = displayed;
			if (displayed)
				setImage(getFXImage.apply(galleryImage));
			else if (getImage() != null)
				getImage().cancel();
		}
	}
	
	@Override
	public int hashCode()
	{
		return galleryImage.hashCode() ^ getFXImage.hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		return !(obj instanceof GalleryImageView) ? false
		        : galleryImage.equals(((GalleryImageView) obj).galleryImage)
		                && getFXImage.equals(((GalleryImageView) obj).getFXImage);
	}
}

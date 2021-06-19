package nigloo.gallerymanager.model;

import java.io.IOException;
import java.util.Objects;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

@JsonAdapter(ImageReference.ImageReferenceTypeAdapter.class)
public class ImageReference
{
	private long imageId;
	private Image image;
	
	@Inject
	private Gallery gallery;
	
	public ImageReference(long imageId)
	{
		Injector.init(this);
		this.imageId = imageId;
		this.image = null;
		
		if (imageId <= 0)
			throw new IllegalArgumentException("imageId must be strictly positive. Got: " + imageId);
		
		registerInstance();
	}
	
	public ImageReference(Image image)
	{
		Injector.init(this);
		this.image = Objects.requireNonNull(image, "image");
		this.imageId = image.getId();
		
		if (!image.isSaved())
			throw new IllegalArgumentException("Image not saved : " + image.getPath());
		
		registerInstance();
	}
	
	// MUST be the LAST instruction of ANY constructor
	private void registerInstance()
	{
		gallery.allImageReferences.add(this);
	}
	
	public Image getImage()
	{
		if (image == null)
			image = gallery.findImage(imageId);
		
		return image;
	}
	
	public Long getImageId()
	{
		return (image != null) ? image.getId() : imageId;
	}
	
	public static TypeAdapter<ImageReference> typeAdapter()
	{
		return new ImageReferenceTypeAdapter();
	}
	
	private static class ImageReferenceTypeAdapter extends TypeAdapter<ImageReference>
	{
		@Override
		public void write(JsonWriter out, ImageReference ref) throws IOException
		{
			out.value(ref.getImageId());
		}
		
		@Override
		public ImageReference read(JsonReader in) throws IOException
		{
			return new ImageReference(in.nextLong());
		}
	};
}
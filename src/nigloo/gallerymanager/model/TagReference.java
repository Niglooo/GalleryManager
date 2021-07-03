package nigloo.gallerymanager.model;

import java.io.IOException;
import java.util.Objects;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

@JsonAdapter(TagReference.TagReferenceTypeAdapter.class)
public class TagReference
{
	private String tagValue;
	private transient Tag tag;
	
	@Inject
	private transient Gallery gallery;
	
	public TagReference(String tagValue)
	{
		this.tagValue = Objects.requireNonNull(tagValue, "tagValue");
		this.tag = null;
		
		Injector.init(this);
		registerInstance();
	}
	
	public TagReference(Tag tag)
	{
		this.tag = Objects.requireNonNull(tag, "tag");
		this.tagValue = tag.getValue();
		
		Injector.init(this);
		registerInstance();
	}
	
	// MUST be the LAST instruction of ANY constructor
	private void registerInstance()
	{
		gallery.allTagReferences.add(this);
	}
	
	public Tag getTag()
	{
		if (tag == null)
			tag = gallery.getTag(tagValue);
		
		return tag;
	}
	
	public String getTagValue()
	{
		return (tag != null) ? tag.getValue() : tagValue;
	}
	
	@Override
	public int hashCode()
	{
		return getTagValue().hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if (obj == this)
			return true;
		if (!(obj instanceof TagReference))
			return false;
		
		return ((TagReference) obj).getTagValue().equals(getTagValue());
	}
	
	public static class TagReferenceTypeAdapter extends TypeAdapter<TagReference>
	{
		@Override
		public void write(JsonWriter out, TagReference ref) throws IOException
		{
			out.value(ref.getTagValue());
		}
		
		@Override
		public TagReference read(JsonReader in) throws IOException
		{
			return new TagReference(in.nextString());
		}
	};
}

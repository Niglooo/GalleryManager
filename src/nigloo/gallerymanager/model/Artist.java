package nigloo.gallerymanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import nigloo.gallerymanager.autodownloader.Downloader;

public class Artist
{
	private TagReference tag;
	@Getter @Setter
	private String name;

	ArrayList<Downloader> autodownloaders = new ArrayList<>();
	
	public Artist()
	{
	}
	
	public Tag getTag()
	{
		return tag == null ? null : tag.getTag();
	}

	public String getTagName()
	{
		return tag == null ? null : tag.getTagName();
	}
	
	public void setTag(Tag tag)
	{
		this.tag = (tag == null) ? null : new TagReference(tag);
	}

	public List<Downloader> getAutodownloaders()
	{
		return Collections.unmodifiableList(autodownloaders);
	}
}

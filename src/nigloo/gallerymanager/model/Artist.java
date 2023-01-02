package nigloo.gallerymanager.model;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import nigloo.gallerymanager.autodownloader.Downloader;

public class Artist
{
	private TagReference tag;
	@Getter @Setter
	private String name;
	
	@Getter
	private List<Downloader> autodownloaders;
	
	public Artist()
	{
	}
	
	public Tag getTag()
	{
		return tag.getTag();
	}
	
	public void setTag(Tag tag)
	{
		this.tag = new TagReference(tag);
	}
}

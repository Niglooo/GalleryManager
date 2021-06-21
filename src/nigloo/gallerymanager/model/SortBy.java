package nigloo.gallerymanager.model;

import java.util.Comparator;

import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

public enum SortBy
{
	NAME(Comparator.comparing(e -> e.getPath().toString(), Utils.NATURAL_ORDER)),
	DATE(Comparator.comparingLong(FileSystemElement::getLastModified));
	
	final Comparator<FileSystemElement> comparator;
	
	private SortBy(Comparator<FileSystemElement> comparator)
	{
		this.comparator = comparator;
	}
}
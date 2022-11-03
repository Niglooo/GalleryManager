package nigloo.gallerymanager.model;

import java.nio.file.Path;
import java.util.Comparator;

import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

public enum SortBy
{
	NAME(Comparator.comparing(FileSystemElement::getPath, SortBy::compareIgnoringExtention)),
	DATE(Comparator.comparingLong(FileSystemElement::getLastModified));
	
	final Comparator<FileSystemElement> comparator;
	
	private SortBy(Comparator<FileSystemElement> comparator)
	{
		this.comparator = comparator;
	}
	
	static private int compareIgnoringExtention(Path p1, Path p2)
	{
		String filename1 = p1.getFileName().toString();
		int posExt1 = filename1.lastIndexOf('.');
		if (posExt1 >= 0)
		{
			String filename2 = p2.getFileName().toString();
			int posExt2 = filename2.lastIndexOf('.');
			if (posExt2 >= 0)
			{
				String path1 = p1.toString();
				path1 = path1.substring(0, path1.lastIndexOf('.'));
				
				String path2 = p2.toString();
				path2 = path2.substring(0, path2.lastIndexOf('.'));
				
				return Utils.NATURAL_ORDER.compare(path1, path2);
			}
		}
		
		return Utils.NATURAL_ORDER.compare(p1.toString(), p2.toString());
	}
}

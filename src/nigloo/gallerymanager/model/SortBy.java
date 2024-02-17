package nigloo.gallerymanager.model;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import nigloo.gallerymanager.ui.FileSystemElement;
import nigloo.tool.Utils;

public enum SortBy
{
	NAME(Comparator.comparing(FileSystemElement::getPath, SortBy::compareIgnoringExtention)),
	DATE(Comparator.comparingLong(FileSystemElement::getLastModified)),
	DEILAN_ORDER(Comparator.comparing(FileSystemElement::getPath, SortBy::deilanOrder))
	;
	
	final Comparator<FileSystemElement> comparator;
	
	SortBy(Comparator<FileSystemElement> comparator)
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

	// new type of customizable orde base on keykords
	static private final List<String> deilanKeywords = List.of("clothed", "topless", "bottomless", "nude", "wet",  "lewd");

	static private int deilanOrder(Path p1, Path p2)
	{
		String filename1 = p1.getFileName().toString();
		String filename2 = p2.getFileName().toString();

		int pos1 = 0;
		for (String keyword : deilanKeywords) {
			if (filename1.toLowerCase(Locale.ROOT).contains(keyword))
				break;
			pos1++;
		}
		int pos2 = 0;
		for (String keyword : deilanKeywords) {
			if (filename2.toLowerCase(Locale.ROOT).contains(keyword))
				break;
			pos2++;
		}

		if (pos1 == pos2)
			return compareIgnoringExtention(p1, p2);
		else
			return pos1 - pos2;
	}
}

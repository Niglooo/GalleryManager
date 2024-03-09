package nigloo.gallerymanager.model;

import java.util.Comparator;
import java.util.Objects;

import com.google.gson.annotations.JsonAdapter;
import nigloo.gallerymanager.model.SortBy.SortByReferenceTypeAdapter;
import nigloo.gallerymanager.ui.FileSystemElement;

public record FileFolderOrder(
		@JsonAdapter(SortByReferenceTypeAdapter.class)
		SortBy sortBy,
		int directoryWeight,
		boolean ascending)
        implements Comparator<FileSystemElement>
{
	public FileFolderOrder
	{
		Objects.requireNonNull(sortBy, "sortBy == null");
		directoryWeight = Math.max(-1, Math.min(directoryWeight, 1));
	}
	
	@Override
	public int compare(FileSystemElement e1, FileSystemElement e2)
	{
		int w1 = e1.isDirectory() ? directoryWeight : 0;
		int w2 = e2.isDirectory() ? directoryWeight : 0;
		if (w1 != w2)
			return w1 - w2;
		
		return ascending ? sortBy.compare(e1, e2) : sortBy.compare(e2, e1);
	}
}

package nigloo.gallerymanager.ui.util;

import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.collections.transformation.TransformationList;
import nigloo.tool.Utils;

public abstract class OffsetMapList<E, F> extends TransformationList<E, F>
{
	protected final int offset;
	
	protected OffsetMapList(ObservableList<? extends F> source, int offset)
	{
		super(source);
		if (offset < 0)
			throw new IllegalArgumentException("offset must be positive");
		this.offset = offset;
	}
	
	@Override
	protected void sourceChanged(Change<? extends F> c)
	{
		fireChange(new OffsetMapListChange(c));
	}
	
	@Override
	public int getSourceIndex(int index)
	{
		return index >= 0 ? index + offset : -1;
	}
	
	@Override
	public int getViewIndex(int index)
	{
		return index - offset;
	}
	
	@Override
	public E get(int index)
	{
		if (index < 0 || index >= size())
			throw new IndexOutOfBoundsException(index);
		
		return fromSource(getSource().get(getSourceIndex(index)));
	}
	
	@Override
	public int size()
	{
		return getSource().size() - offset;
	}
	
	protected abstract F toSource(E elem);
	
	protected abstract E fromSource(F sourceElem);
	
	private class OffsetMapListChange extends ListChangeListener.Change<E>
	{
		private final ListChangeListener.Change<F> c;
		
		public OffsetMapListChange(ListChangeListener.Change<? extends F> originalChange)
		{
			super(OffsetMapList.this);
			this.c = Utils.cast(originalChange);
		}
		
		@Override
		public boolean next()
		{
			return c.next();
		}
		
		@Override
		public void reset()
		{
			c.reset();
		}
		
		@Override
		public int getFrom()
		{
			return getViewIndex(c.getFrom());
		}
		
		@Override
		public int getTo()
		{
			return getViewIndex(c.getFrom());
		}
		
		@Override
		public List<E> getRemoved()
		{
			return c.getRemoved().stream().map(OffsetMapList.this::fromSource).toList();
		}
		
		@Override
		protected int[] getPermutation()
		{
			if (!c.wasPermutated())
				return new int[0];
			
			int[] newPerm = new int[c.getTo() - c.getFrom()];
			for (int i = c.getFrom() ; i < c.getTo() ; ++i)
			{
				newPerm[i - c.getFrom()] = getViewIndex(c.getPermutation(i));
			}
			
			return newPerm;
		}
	}
}

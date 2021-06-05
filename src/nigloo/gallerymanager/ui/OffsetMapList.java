package nigloo.gallerymanager.ui;

import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.collections.transformation.TransformationList;
import nigloo.tool.Utils;

abstract class OffsetMapList<E, F> extends TransformationList<E, F>
{
	protected final int offset;

	OffsetMapList(ObservableList<? extends F> source, int offset)
	{
		super(source);
		this.offset = offset;
	}
	
	@Override
	protected void sourceChanged(Change<? extends F> c)
	{
		fireChange(new TileListChange(c));
	}
	
	@Override
	public int getSourceIndex(int index)
	{
		return index + offset;
	}
	
	@Override
	public int getViewIndex(int index)
	{
		return index - offset;
	}
	
	@Override
	public E get(int index)
	{
		return fromSource(getSource().get(getSourceIndex(index)));
	}
	
	@Override
	public int size()
	{
		return getSource().size() - offset;
	}
	
	protected abstract F toSource(E elem);
	
	protected abstract E fromSource(F sourceElem);
	
	private class TileListChange extends ListChangeListener.Change<E>
	{
		private final ListChangeListener.Change<F> c;
		
		public TileListChange(ListChangeListener.Change<? extends F> originalChange)
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
package nigloo.gallerymanager.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.scene.control.FocusModel;
import javafx.scene.control.MultipleSelectionModel;

public class GridFocusSelectionManager<T>
{
	private final ObservableList<T> source;
	private final ObservableList<T> selection;
	private T focusItem;
	
	private final GridMultipleSelectionModel selectionModel = new GridMultipleSelectionModel();
	
	public GridFocusSelectionManager(ObservableList<T> source)
	{
		super();
		this.source = source;
		this.selection = FXCollections.observableArrayList();
		this.selectionModel.select0(-1);
		this.focusItem = null;
		
		this.source.addListener((Change<? extends T> c) ->
		{
			selection.removeIf(item -> !source.contains(item));
			if (!source.contains(focusItem))
				focusItem = null;
			
			if (!source.contains(selectionModel.getSelectedItem()))
			{
				if (selection.isEmpty())
				{
					selectionModel.select0(-1);
				}
				else
				{
					selectionModel.select0(selection.size() - 1);
				}
			}
		});
	}
	
	public FocusModel<T> getFocusModel()
	{
		return null;// TODO
	}
	
	public MultipleSelectionModel<T> getSelectionModel()
	{
		return selectionModel;
	}
	
	public void click(T item, boolean shiftDown, boolean controlDown)
	{
		if (shiftDown)
		{
			int focusIdx = (focusItem == null) ? 0 : source.indexOf(focusItem);
			int itemIdx = source.indexOf(item);
			
			selectionModel.select0(itemIdx);
			
			List<T> itemsToSelect = new ArrayList<>(source.subList(Math.min(focusIdx, itemIdx),
			                                                       Math.max(focusIdx, itemIdx) + 1));
			if (itemIdx < focusIdx)
				Collections.reverse(itemsToSelect);
			
			if (controlDown)
			{
				itemsToSelect.removeAll(selection);
				selection.addAll(itemsToSelect);
			}
			else
				selection.setAll(itemsToSelect);
		}
		else if (controlDown)
		{
			focusItem = item;
			if (selection.remove(item))
			{
				if (selection.isEmpty())
				{
					selectionModel.select0(-1);
				}
				else
				{
					selectionModel.select0(selection.get(selection.size() - 1));
				}
			}
			else
			{
				selectionModel.select0(item);
				selection.add(item);
			}
		}
		else
		{
			focusItem = item;
			selectionModel.select0(item);
			selection.setAll(List.of(item));
		}
	}

	private class GridMultipleSelectionModel extends MultipleSelectionModel<T>
	{
		@Override
		public ObservableList<Integer> getSelectedIndices()
		{
			throw new UnsupportedOperationException("getSelectedIndices");
		}
		
		@Override
		public ObservableList<T> getSelectedItems()
		{
			return selection;
		}
		
		void select0(int index)
		{
			super.setSelectedItem(index < 0 ? null : source.get(index));
			super.setSelectedIndex(index);
		}
		
		void select0(T item)
		{
			super.setSelectedItem(item);
			super.setSelectedIndex(source.indexOf(item));
		}
		
		@Override
		public void selectIndices(int index, int... indices)
		{
			IntStream indexes = IntStream.of(index);
			if (indices != null && indices.length > 0)
				indexes = IntStream.concat(indexes, IntStream.of(indices));
			List<T> newSelection = indexes.filter(i -> i >= 0 && i < source.size()).mapToObj(source::get).toList();
			
			setSelectedItem(newSelection.get(newSelection.size() - 1));
			setSelectedIndex(newSelection.size() - 1);
			focusItem = getSelectedItem();
			selection.setAll(newSelection);
		}
		
		@Override
		public void selectAll()
		{
			setSelectedItem(source.get(source.size() - 1));
			setSelectedIndex(source.size() - 1);
			selection.setAll(source);
		}
		
		@Override
		public void selectFirst()
		{
			if (source.isEmpty())
				return;
			
			select(source.get(0));
		}
		
		@Override
		public void selectLast()
		{
			select(source.get(source.size() - 1));
		}
		
		@Override
		public void clearAndSelect(int index)
		{
			if (index < 0 || index >= source.size())
				return;
			
			clearAndSelect(source.get(index));
		}
		
		public void clearAndSelect(T item)
		{
			focusItem = item;
			setSelectedIndex(source.indexOf(item));
			setSelectedItem(item);
			selection.setAll(List.of(item));
		}
		
		@Override
		public void select(int index)
		{
			if (index < 0 || index >= source.size())
				return;
			
			select(source.get(index));
		}
		
		@Override
		public void select(T item)
		{
			if (!source.contains(item))
				return;
			
			focusItem = item;
			setSelectedItem(item);
			setSelectedIndex(source.indexOf(item));
			
			if (!selection.contains(item))
				selection.add(item);
		}
		
		@Override
		public void clearSelection(int index)
		{
			if (index < 0 || index >= source.size())
				return;
			
			if (index == getSelectedIndex())
			{
				setSelectedItem(null);
				setSelectedIndex(-1);
			}
			focusItem = source.get(index);
			selection.remove(focusItem);
		}
		
		@Override
		public void clearSelection()
		{
			setSelectedItem(null);
			setSelectedIndex(-1);
			selection.clear();
		}
		
		@Override
		public boolean isSelected(int index)
		{
			if (index < 0 || index >= source.size())
				return false;
			
			return selection.contains(source.get(index));
		}
		
		@Override
		public boolean isEmpty()
		{
			return selection.isEmpty();
		}
		
		@Override
		public void selectPrevious()
		{
			if (getSelectedIndex() == -1)
				return;
			
			select(getSelectedIndex() - 1);
		}
		
		@Override
		public void selectNext()
		{
			if (getSelectedIndex() == -1)
				return;
			
			select(getSelectedIndex() + 1);
		}
	}
}

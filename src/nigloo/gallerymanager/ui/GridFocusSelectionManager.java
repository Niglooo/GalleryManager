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
	private final ObservableList<T> selection = FXCollections.observableArrayList();
	
	private final GridFocusModel focusModel = new GridFocusModel();
	private final GridSelectionModel selectionModel = new GridSelectionModel();
	
	public GridFocusSelectionManager(ObservableList<T> source)
	{
		this.source = source;
		this.selectionModel.select0(-1);
		
		this.source.addListener((Change<? extends T> c) ->
		{
			selection.removeIf(item -> !source.contains(item));
			if (!source.contains(focusModel.getFocusedItem()))
				focusModel.focus(-1);
			
			if (!source.contains(selectionModel.getSelectedItem()))
			{
				selectionModel.select0(-1);
			}
		});
	}
	
	public FocusModel<T> getFocusModel()
	{
		return focusModel;
	}
	
	public MultipleSelectionModel<T> getSelectionModel()
	{
		return selectionModel;
	}
	
	public void click(T item, boolean shiftDown, boolean controlDown)
	{
		int itemIdx = source.indexOf(item);
		focusModel.focus(itemIdx);
		
		if (shiftDown)
		{
			// 1 selectedIndex
			// 2 focusedIndex
			// 3 first item
			int anchorIdx = selectionModel.getSelectedIndex() >= 0 ? selectionModel.getSelectedIndex()
			        : focusModel.getFocusedIndex() >= 0 ? focusModel.getFocusedIndex() : 0;
			
			selectionModel.select0(anchorIdx);
			
			List<T> itemsToSelect = new ArrayList<>(source.subList(Math.min(anchorIdx, itemIdx),
			                                                       Math.max(anchorIdx, itemIdx) + 1));
			if (itemIdx < anchorIdx)
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
			if (selection.remove(item))
			{
				selectionModel.select0(-1);
			}
			else
			{
				selectionModel.select0(itemIdx);
				selection.add(item);
			}
		}
		else
		{
			selectionModel.select0(itemIdx);
			selection.setAll(List.of(item));
		}
	}

	private class GridSelectionModel extends MultipleSelectionModel<T>
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
			
			select0(newSelection.get(newSelection.size() - 1));
			selection.setAll(newSelection);
		}
		
		@Override
		public void selectAll()
		{
			select0(source.size() - 1);
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
			select0(item);
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
			
			select0(item);
			
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
				select0(-1);
			}
			
			selection.remove(source.get(index));
		}
		
		@Override
		public void clearSelection()
		{
			select0(-1);
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
	
	private class GridFocusModel extends FocusModel<T>
	{
		@Override
		protected int getItemCount()
		{
			return source.size();
		}
		
		@Override
		protected T getModelItem(int index)
		{
			return index >= 0 ? source.get(index) : null;
		}
	}
}

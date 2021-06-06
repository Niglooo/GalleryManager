package nigloo.gallerymanager.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javafx.beans.Observable;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.EnumConverter;
import javafx.css.converter.SizeConverter;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import nigloo.tool.Utils;

public class LargeVerticalTilePane extends Region
{
	private static final double DEFAULT_TILE_WIDTH = 100;
	private static final double DEFAULT_TILE_HEIGHT = 100;
	private static final double DEFAULT_HGAP = 0;
	private static final double DEFAULT_VGAP = 0;
	private static final Pos DEFAULT_TILE_ALIGNMENT = Pos.CENTER;
	
	private static final int TILE_LIST_OFFSET = 2;
	private final ScrollBar vScrollBar;
	private final SimpleMultipleSelectionModel<Node> selectionModel;
	private final ObservableList<Node> tiles;

	private final Region selectionRegion;
	private Point2D selectionRegionOrigin = null;
	private double selectionRegionOriginYOffset = 0;
	private Point2D currentMousePosition = null;
	private List<Node> lastSelectionRegionNodes = null;
	
	private boolean invertingSelection = false;
	private List<Node> selectedWhenStartSelectionDrag = null;
	
	public LargeVerticalTilePane()
	{
		super();
		vScrollBar = new ScrollBar();
		vScrollBar.setOrientation(Orientation.VERTICAL);
		vScrollBar.setBlockIncrement(1000);
		vScrollBar.setUnitIncrement(100);
		vScrollBar.valueProperty().addListener((obs, oldValue, newValue) ->
		{
			upadteTilesDisposition();
			updateSelectionRegion();
			requestLayout();
			
		});
		getChildren().add(vScrollBar);
		
		tiles = new OffsetMapList<Node, Node>(getChildren(), TILE_LIST_OFFSET)
		{
			@Override
			protected Node toSource(Node unwrapped)
			{
				return wrapNode(unwrapped);
			}
			
			@Override
			protected Node fromSource(Node wrapped)
			{
				return unwrapNode((TileWrapper) wrapped);
			}
			
			@Override
			public boolean setAll(Collection<? extends Node> col)
			{
				ObservableList<Node> source = Utils.cast(getSource());
				List<Node> before = source.stream().skip(offset).map(this::fromSource).toList();
				
				HashSet<Node> newNodes = new HashSet<>(col);
				newNodes.removeAll(before);
				for (Node newNode : newNodes)
					newNode.setVisible(false);
				
				ArrayList<Node> allNodes = new ArrayList<>(offset + col.size());
				allNodes.addAll(source.subList(0, offset));
				col.stream().map(this::toSource).forEachOrdered(allNodes::add);
				source.setAll(allNodes);
				
				return true;
			}
		};
		
		selectionModel = new SimpleMultipleSelectionModel<>(tiles);
		selectionModel.getSelectedItems().addListener((Observable observable) -> requestLayout());
		
		selectionRegion = new Region();
		BorderStroke stroke = new BorderStroke(Color.rgb(0, 120, 215),
		                                       BorderStrokeStyle.SOLID,
		                                       null,
		                                       new BorderWidths(1));
		selectionRegion.setBorder(new Border(stroke, stroke, stroke, stroke));
		selectionRegion.setBackground(new Background(new BackgroundFill(Color.rgb(0, 120, 215, 0.4),
		                                                                null,
		                                                                null)));
		selectionRegion.setVisible(false);
		selectionRegion.setViewOrder(-1);
		getChildren().add(selectionRegion);
		
		addEventHandler(ScrollEvent.SCROLL, event ->
		{
			vScrollBar.setValue(vScrollBar.getValue() - event.getDeltaY());
		});
		
		addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
		{
			if (event.getButton() == MouseButton.PRIMARY)
			{
				if (!event.isShiftDown() && !event.isControlDown())
					selectionModel.clearSelection();
				
				if (event.isControlDown())
				{
					invertingSelection = true;
					selectedWhenStartSelectionDrag = List.copyOf(selectionModel.getSelectedItems());
				}
				else
					invertingSelection = false;
				
				selectionRegionOrigin = new Point2D(event.getX(), event.getY());
				selectionRegionOriginYOffset = actualYOffset;
				lastSelectionRegionNodes = null;
			}
		});
		
		// Region are not focusTraversable by defaut so they don't receive key events.
		// Register the event listener on the scene instead
		sceneProperty().addListener((obs, oldValue, newValue) ->
		{
			newValue.addEventHandler(KeyEvent.KEY_RELEASED, event ->
			{
				if (invertingSelection && !event.isControlDown())
				{
					invertingSelection = false;
					selectedWhenStartSelectionDrag = null;
				}
			});
		});
		
		addEventHandler(MouseEvent.MOUSE_RELEASED, event ->
		{
			if (event.getButton() == MouseButton.PRIMARY)
			{
				selectionRegionOrigin = null;
				selectionRegion.setVisible(false);
				lastSelectionRegionNodes = null;
			}
		});
		
		addEventHandler(MouseEvent.MOUSE_DRAGGED, event ->
		{
			if (event.isPrimaryButtonDown() && selectionRegionOrigin != null)
			{
				currentMousePosition = new Point2D(event.getX(), event.getY());
				updateSelectionRegion();
				
				List<Node> selectionRegionNode = getManagedChildren().stream()
				                                                     .skip(TILE_LIST_OFFSET)
				                                                     .filter(n -> n.getBoundsInParent()
				                                                                   .intersects(selectionRegion.getBoundsInParent()))
				                                                     .map(TileWrapper.class::cast)
				                                                     .map(this::unwrapNode)
				                                                     .toList();
				
				if (lastSelectionRegionNodes == null)
					lastSelectionRegionNodes = List.of();
				
				List<Node> enteringNodes = new ArrayList<>(selectionRegionNode);
				enteringNodes.removeAll(lastSelectionRegionNodes);
				
				List<Node> exitingNodes = new ArrayList<>(lastSelectionRegionNodes);
				exitingNodes.removeAll(selectionRegionNode);
				
				List<Node> toSelect = new ArrayList<>();
				List<Node> toUnselect = new ArrayList<>();
				
				enteringNodes.forEach(node ->
				{
					if (invertingSelection)
					{
						if (selectedWhenStartSelectionDrag.contains(node))
							toUnselect.add(node);
						else
							toSelect.add(node);
					}
					else if (!selectionModel.getSelectedItems().contains(node))
						toSelect.add(node);
				});
				
				exitingNodes.forEach(node ->
				{
					if (invertingSelection)
					{
						if (selectedWhenStartSelectionDrag.contains(node))
							toSelect.add(node);
						else
							toUnselect.add(node);
					}
					else
						toUnselect.add(node);
				});
				
				lastSelectionRegionNodes = selectionRegionNode;
				
				selectionModel.getSelectedItems().addAll(toSelect);
				selectionModel.getSelectedItems().removeAll(toUnselect);
				
				selectionRegion.setVisible(true);
			}
		});
		
		//TODO css
		setBackground(new Background(new BackgroundFill(Color.WHITE, null, null)));
	}
	
	public ObservableList<Node> getTiles()
	{
		return tiles;
	}
	
	public MultipleSelectionModel<Node> getSelectionModel()
	{
		return selectionModel;
	}
	
	/**
	 * The width of each tile.
	 * 
	 * @return the width of each tile
	 */
	public final DoubleProperty tileWidthProperty()
	{
		if (tileWidth == null)
		{
			tileWidth = new StyleableDoubleProperty(DEFAULT_TILE_WIDTH)
			{
				@Override
				public void invalidated()
				{
					requestLayout();
				}
				
				@Override
				public CssMetaData<LargeVerticalTilePane, Number> getCssMetaData()
				{
					return StyleableProperties.TILE_WIDTH;
				}
				
				@Override
				public Object getBean()
				{
					return LargeVerticalTilePane.this;
				}
				
				@Override
				public String getName()
				{
					return "tileWidth";
				}
			};
		}
		return tileWidth;
	}
	
	private DoubleProperty tileWidth;
	
	public final void setTileWidth(double value)
	{
		tileWidthProperty().set(value);
	}
	
	public final double getTileWidth()
	{
		return tileWidth == null ? DEFAULT_TILE_WIDTH : tileWidth.get();
	}
	
	/**
	 * The height of each tile.
	 * 
	 * @return the height of each tile
	 */
	public final DoubleProperty tileHeightProperty()
	{
		if (tileHeight == null)
		{
			tileHeight = new StyleableDoubleProperty(DEFAULT_TILE_HEIGHT)
			{
				@Override
				public void invalidated()
				{
					requestLayout();
				}
				
				@Override
				public CssMetaData<LargeVerticalTilePane, Number> getCssMetaData()
				{
					return StyleableProperties.TILE_HEIGHT;
				}
				
				@Override
				public Object getBean()
				{
					return LargeVerticalTilePane.this;
				}
				
				@Override
				public String getName()
				{
					return "tileHeight";
				}
			};
		}
		return tileHeight;
	}
	
	private DoubleProperty tileHeight;
	
	public final void setTileHeight(double value)
	{
		tileHeightProperty().set(value);
	}
	
	public final double getTileHeight()
	{
		return tileHeight == null ? DEFAULT_TILE_HEIGHT : tileHeight.get();
	}
	
	/**
	 * The amount of horizontal space between each tile in a row.
	 * 
	 * @return the amount of horizontal space between each tile in a row
	 */
	public final DoubleProperty hgapProperty()
	{
		if (hgap == null)
		{
			hgap = new StyleableDoubleProperty(DEFAULT_HGAP)
			{
				@Override
				public void invalidated()
				{
					requestLayout();
				}
				
				@Override
				public CssMetaData<LargeVerticalTilePane, Number> getCssMetaData()
				{
					return StyleableProperties.HGAP;
				}
				
				@Override
				public Object getBean()
				{
					return LargeVerticalTilePane.this;
				}
				
				@Override
				public String getName()
				{
					return "hgap";
				}
			};
		}
		return hgap;
	}
	
	private DoubleProperty hgap;
	
	public final void setHgap(double value)
	{
		hgapProperty().set(value);
	}
	
	public final double getHgap()
	{
		return hgap == null ? DEFAULT_HGAP : hgap.get();
	}
	
	/**
	 * The amount of vertical space between each tile in a column.
	 * 
	 * @return the amount of vertical space between each tile in a column
	 */
	public final DoubleProperty vgapProperty()
	{
		if (vgap == null)
		{
			vgap = new StyleableDoubleProperty(DEFAULT_VGAP)
			{
				@Override
				public void invalidated()
				{
					requestLayout();
				}
				
				@Override
				public CssMetaData<LargeVerticalTilePane, Number> getCssMetaData()
				{
					return StyleableProperties.VGAP;
				}
				
				@Override
				public Object getBean()
				{
					return LargeVerticalTilePane.this;
				}
				
				@Override
				public String getName()
				{
					return "vgap";
				}
			};
		}
		return vgap;
	}
	
	private DoubleProperty vgap;
	
	public final void setVgap(double value)
	{
		vgapProperty().set(value);
	}
	
	public final double getVgap()
	{
		return vgap == null ? DEFAULT_VGAP : vgap.get();
	}
	
	/**
	 * The default alignment of each child within its tile. This may be overridden
	 * on individual children by setting the child's alignment constraint.
	 * 
	 * @return the default alignment of each child within its tile
	 */
	public final ObjectProperty<Pos> tileAlignmentProperty()
	{
		if (tileAlignment == null)
		{
			tileAlignment = new StyleableObjectProperty<Pos>(DEFAULT_TILE_ALIGNMENT)
			{
				@Override
				public void invalidated()
				{
					requestLayout();
				}
				
				@Override
				public CssMetaData<LargeVerticalTilePane, Pos> getCssMetaData()
				{
					return StyleableProperties.TILE_ALIGNMENT;
				}
				
				@Override
				public Object getBean()
				{
					return LargeVerticalTilePane.this;
				}
				
				@Override
				public String getName()
				{
					return "tileAlignment";
				}
			};
		}
		return tileAlignment;
	}
	
	private ObjectProperty<Pos> tileAlignment;
	
	public final void setTileAlignment(Pos value)
	{
		tileAlignmentProperty().set(value);
	}
	
	public final Pos getTileAlignment()
	{
		return tileAlignment == null ? DEFAULT_TILE_ALIGNMENT : tileAlignment.get();
	}
	
	private Pos getTileAlignmentInternal()
	{
		Pos localPos = getTileAlignment();
		return localPos == null ? DEFAULT_TILE_ALIGNMENT : localPos;
	}
	
	@Override
	protected double computeMinWidth(double height)
	{
		return getInsets().getLeft() + getInsets().getRight() + vScrollBar.minWidth(height);
	}
	
	@Override
	protected double computePrefWidth(double height)
	{
		double insideHeight = height - getInsets().getTop() - getInsets().getBottom();
		
		int rows = 1 + (int) ((Math.max(0, insideHeight - getTileHeight())) / (getTileHeight() + getVgap()));
		
		int prefColumns = (int) Math.ceil((double) getManagedChildren().size() / (double) rows);
		
		return getInsets().getLeft() + getInsets().getRight() + vScrollBar.prefWidth(height)
		        + prefColumns * getTileWidth() + (prefColumns - 1) * getHgap();
	}
	
	@Override
	protected double computeMinHeight(double width)
	{
		return getInsets().getTop() + getInsets().getBottom();
	}
	
	@Override
	protected double computePrefHeight(double width)
	{
		double insideWidth = width - getInsets().getLeft() - getInsets().getRight();
		
		int prefColumns = 1 + (int) ((Math.max(0, insideWidth - getTileWidth())) / (getTileWidth() + getHgap()));
		
		int rows = (int) Math.ceil(getManagedChildren().size() / (double) prefColumns);
		
		return getInsets().getTop() + getInsets().getBottom() + rows * getTileHeight() + (rows - 1) * getVgap();
	}
	
	private int actualRows = 0;
	private int actualColumns = 0;
	private double actualYOffset = 0;
	
	private void upadteTilesDisposition()
	{
		List<Node> managed = getManagedChildren();
		double width = getWidth();
		double height = getHeight();
		double top = snapSpaceY(getInsets().getTop());
		double left = snapSpaceX(getInsets().getLeft());
		double bottom = snapSpaceY(getInsets().getBottom());
		double right = snapSpaceX(getInsets().getRight());
		double vgap = snapSpaceY(getVgap());
		double hgap = snapSpaceX(getHgap());
		double insideWidth = width - left - right - vScrollBar.getWidth();
		
		double tileWidth = getTileWidth();
		double tileHeight = getTileHeight();
		
		actualColumns = 1 + (int) ((Math.max(0, insideWidth - tileWidth)) / (tileWidth + hgap));
		actualRows = (int) Math.ceil(managed.size() / (double) actualColumns);
		
		double contentBottom = top + bottom + actualRows * tileHeight + (actualRows - 1) * vgap;
		
		vScrollBar.setMin(0);
		vScrollBar.setMax(contentBottom);
		vScrollBar.setValue(Math.max(0, Math.min(vScrollBar.getValue(), contentBottom)));
		vScrollBar.setVisibleAmount(height);
		vScrollBar.setDisable(height >= contentBottom);
		
		actualYOffset = -(vScrollBar.getValue() / contentBottom) * Math.max(0, contentBottom - height);
	}
	
	private Collection<TileWrapper> lastVisibleTiles = new ArrayList<>();
	
	@Override
	protected void layoutChildren()
	{
		upadteTilesDisposition();
		
		List<Node> managed = getManagedChildren();
		double width = getWidth();
		double height = getHeight();
		double top = snapSpaceY(getInsets().getTop());
		double left = snapSpaceX(getInsets().getLeft());
		double vgap = snapSpaceY(getVgap());
		double hgap = snapSpaceX(getHgap());
		
		double tileWidth = getTileWidth();
		double tileHeight = getTileHeight();
		
		layoutInArea(vScrollBar,
		             0,
		             0,
		             width,
		             height,
		             -1/* baselineOffset */,
		             null/* getMargin(child) */,
		             HPos.RIGHT,
		             VPos.TOP);
		
		Collection<TileWrapper> visibleNodes = new ArrayList<>();
		
		int c = 0;
		int r = 0;
		for (int i = TILE_LIST_OFFSET ; i < managed.size() ; i++)
		{
			TileWrapper tile = (TileWrapper) managed.get(i);
			
			double tileX = left + (c * (tileWidth + hgap));
			double tileY = actualYOffset + top + (r * (tileHeight + vgap));
			
			if (tileY + tileHeight >= 0 && tileY < height)
			{
				tile.selectedProperty().set(selectionModel.getSelectedItems().contains(unwrapNode(tile)));
				tile.setVisible(true);
				
				layoutInArea(tile,
				             tileX,
				             tileY,
				             tileWidth,
				             tileHeight,
				             -1/* baselineOffset */,
				             null/* getMargin(child) */,
				             getTileAlignmentInternal().getHpos(),
				             getTileAlignmentInternal().getVpos());
				
				visibleNodes.add(tile);
			}
			else
				tile.setVisible(false);
			
			if (++c == actualColumns)
			{
				++r;
				c = 0;
			}
		}
		
		lastVisibleTiles.removeAll(visibleNodes);
		for (Node node : lastVisibleTiles)
			node.setVisible(false);
		lastVisibleTiles = visibleNodes;
	}
	
	private TileWrapper wrapNode(Node unwrapped)
	{
		if (unwrapped.getParent() instanceof TileWrapper)
			return (TileWrapper) unwrapped.getParent();
		else
			return new TileWrapper(unwrapped);
	}
	
	private Node unwrapNode(TileWrapper wrapped)
	{
		return wrapped.getContent();
	}
	
	private class TileWrapper extends StackPane
	{
		private static final CornerRadii CORNER_RADIUS = null;
		private static final double BORDER_WIDTH = 2;
		
		private static final Background SELECTED_REGION_BACKGROUND = new Background(new BackgroundFill(Color.rgb(204,
		                                                                                                         232,
		                                                                                                         255),
		                                                                                               CORNER_RADIUS,
		                                                                                               null));
		
		private static final Border SELECTED_REGION_BORDER;
		static
		{
			BorderStroke stroke = new BorderStroke(Color.rgb(153, 209, 255),
			                                       BorderStrokeStyle.SOLID,
			                                       CORNER_RADIUS,
			                                       new BorderWidths(BORDER_WIDTH));
			SELECTED_REGION_BORDER = new Border(stroke, stroke, stroke, stroke);
		}
		
		private static final Background HOVER_REGION_BACKGROUND = new Background(new BackgroundFill(Color.rgb(229,
		                                                                                                      243,
		                                                                                                      255),
		                                                                                            CORNER_RADIUS,
		                                                                                            null));
		
		private final Region backgroundRegion;
		private final Node content;
		private final Region borderRegion;
		
		private final BooleanProperty selected;
		
		public TileWrapper(Node content)
		{
			super();
			
			selected = new SimpleBooleanProperty(this, "selected", true);
			
			backgroundRegion = new Region();
			backgroundRegion.backgroundProperty().bind(new ObjectBinding<Background>()
			{
				{
					bind(selected, hoverProperty());
				}
				
				@Override
				protected Background computeValue()
				{
					if (selected.get())
						return SELECTED_REGION_BACKGROUND;
					else if (hoverProperty().get())
						return HOVER_REGION_BACKGROUND;
					else
						return Background.EMPTY;
				}
			});
			
			borderRegion = new Region();
			borderRegion.borderProperty().bind(new ObjectBinding<Border>()
			{
				{
					bind(selected);
				}
				
				@Override
				protected Border computeValue()
				{
					if (selected.get())
						return SELECTED_REGION_BORDER;
					else
						return Border.EMPTY;
				}
			});
			
			this.content = content;

			getChildren().addAll(backgroundRegion, content, borderRegion);
			setAlignment(content, getTileAlignment());
			
			visibleProperty().bindBidirectional(content.visibleProperty());
			
			addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
			{
				if (event.isPrimaryButtonDown())
				{
					selectionModel.click(content, event.isShiftDown(), event.isControlDown());
					event.consume();
				}
			});
		}
		
		public Node getContent()
		{
			return content;
		}
		
		public BooleanProperty selectedProperty()
		{
			return selected;
		}
	}
	
	private void updateSelectionRegion()
	{
		if (selectionRegionOrigin == null)
			return;
		
		double yOrigin = selectionRegionOrigin.getY() - selectionRegionOriginYOffset + actualYOffset;
		
		selectionRegion.setLayoutX(Math.min(selectionRegionOrigin.getX(), currentMousePosition.getX()));
		selectionRegion.setLayoutY(Math.min(yOrigin, currentMousePosition.getY()));
		
		selectionRegion.setPrefWidth(Math.abs(selectionRegionOrigin.getX() - currentMousePosition.getX()));
		selectionRegion.setPrefHeight(Math.abs(yOrigin - currentMousePosition.getY()));
		
		selectionRegion.autosize();
	}
	
	/***************************************************************************
	 * * Stylesheet Handling * *
	 **************************************************************************/
	
	/*
	 * Super-lazy instantiation pattern from Bill Pugh.
	 */
	private static class StyleableProperties
	{
		private static final CssMetaData<LargeVerticalTilePane, Number> TILE_WIDTH = new CssMetaData<LargeVerticalTilePane, Number>("-fx-tile-width",
		                                                                                                                  SizeConverter.getInstance(),
		                                                                                                                  DEFAULT_TILE_WIDTH)
		{
			
			@Override
			public boolean isSettable(LargeVerticalTilePane node)
			{
				return node.tileWidth == null || !node.tileWidth.isBound();
			}
			
			@Override
			@SuppressWarnings("unchecked")
			public StyleableProperty<Number> getStyleableProperty(LargeVerticalTilePane node)
			{
				return (StyleableProperty<Number>) node.tileWidthProperty();
			}
		};
		
		private static final CssMetaData<LargeVerticalTilePane, Number> TILE_HEIGHT = new CssMetaData<LargeVerticalTilePane, Number>("-fx-tile-height",
		                                                                                                                   SizeConverter.getInstance(),
		                                                                                                                   DEFAULT_TILE_HEIGHT)
		{
			
			@Override
			public boolean isSettable(LargeVerticalTilePane node)
			{
				return node.tileHeight == null || !node.tileHeight.isBound();
			}
			
			@Override
			@SuppressWarnings("unchecked")
			public StyleableProperty<Number> getStyleableProperty(LargeVerticalTilePane node)
			{
				return (StyleableProperty<Number>) node.tileHeightProperty();
			}
		};
		
		private static final CssMetaData<LargeVerticalTilePane, Number> HGAP = new CssMetaData<LargeVerticalTilePane, Number>("-fx-hgap",
		                                                                                                            SizeConverter.getInstance(),
		                                                                                                            DEFAULT_HGAP)
		{
			
			@Override
			public boolean isSettable(LargeVerticalTilePane node)
			{
				return node.hgap == null || !node.hgap.isBound();
			}
			
			@Override
			@SuppressWarnings("unchecked")
			public StyleableProperty<Number> getStyleableProperty(LargeVerticalTilePane node)
			{
				return (StyleableProperty<Number>) node.hgapProperty();
			}
		};
		
		private static final CssMetaData<LargeVerticalTilePane, Number> VGAP = new CssMetaData<LargeVerticalTilePane, Number>("-fx-vgap",
		                                                                                                            SizeConverter.getInstance(),
		                                                                                                            DEFAULT_VGAP)
		{
			
			@Override
			public boolean isSettable(LargeVerticalTilePane node)
			{
				return node.vgap == null || !node.vgap.isBound();
			}
			
			@Override
			@SuppressWarnings("unchecked")
			public StyleableProperty<Number> getStyleableProperty(LargeVerticalTilePane node)
			{
				return (StyleableProperty<Number>) node.vgapProperty();
			}
		};
		
		private static final CssMetaData<LargeVerticalTilePane, Pos> TILE_ALIGNMENT = new CssMetaData<LargeVerticalTilePane, Pos>("-fx-tile-alignment",
		                                                                                                                new EnumConverter<Pos>(Pos.class),
		                                                                                                                DEFAULT_TILE_ALIGNMENT)
		{
			
			@Override
			public boolean isSettable(LargeVerticalTilePane node)
			{
				return node.tileAlignment == null || !node.tileAlignment.isBound();
			}
			
			@Override
			@SuppressWarnings("unchecked")
			public StyleableProperty<Pos> getStyleableProperty(LargeVerticalTilePane node)
			{
				return (StyleableProperty<Pos>) node.tileAlignmentProperty();
			}
		};
		
		private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES;
		static
		{
			final List<CssMetaData<? extends Styleable, ?>> styleables = new ArrayList<CssMetaData<? extends Styleable, ?>>(Region.getClassCssMetaData());
			styleables.add(TILE_WIDTH);
			styleables.add(TILE_HEIGHT);
			styleables.add(HGAP);
			styleables.add(VGAP);
			styleables.add(TILE_ALIGNMENT);
			
			STYLEABLES = Collections.unmodifiableList(styleables);
		}
	}
	
	/**
	 * @return The CssMetaData associated with this class, which may include the
	 *         CssMetaData of its superclasses.
	 * @since JavaFX 8.0
	 */
	public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData()
	{
		return StyleableProperties.STYLEABLES;
	}
	
	/**
	 * {@inheritDoc}
	 *
	 * @since JavaFX 8.0
	 */
	
	@Override
	public List<CssMetaData<? extends Styleable, ?>> getCssMetaData()
	{
		return getClassCssMetaData();
	}
}
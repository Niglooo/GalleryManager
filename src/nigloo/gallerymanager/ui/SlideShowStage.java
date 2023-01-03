package nigloo.gallerymanager.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.kordamp.ikonli.javafx.FontIcon;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectPropertyBase;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaPlayer.Status;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.SlideShowParameters.VideoParameters;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.util.ImageCache;
import nigloo.tool.StrongReference;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.javafx.FXUtils;
import nigloo.tool.thread.SafeThread;
import nigloo.tool.thread.ThreadStopException;

public class SlideShowStage extends Stage
{
	private static final int NO_CURRENT_IMAGE_INDEX = -1;
	
	private static Rectangle2D screenSize = Screen.getPrimary().getBounds();
	
	@Inject
	private Gallery gallery;
	@Inject
	private ImageCache imageCache;
	
	private List<Image> imagesOrdered = null;
	private List<Image> images = null;
	private volatile int currentImageIdx;
	
	private final CurrentImageProperty currentImageProperty = new CurrentImageProperty();
	
	private final StackPane contentRoot;
	private final ImageView imageView;
	private final MediaView mediaView;
	private final InfoZone infoZone;
	private final VideoControl videoControl;
	private final Label notificationLabel;
	private final FadeTransition notificationLabelEffectTL;
	
	private final SimpleBooleanProperty altDown;
	
	private final VideoParameters parametersPreviousVideo;
	
	private final Timeline autoplay;
	private final ImageLoaderDaemon fullImageUpdatingThread;
	

	public SlideShowStage(List<Image> images, int startingIndex)
	{
		assert images.size() > 0;
		assert startingIndex >= 0 && startingIndex < images.size();
		
		Injector.init(this);
		
		this.imagesOrdered = List.copyOf(images);
		
		currentImageIdx = NO_CURRENT_IMAGE_INDEX;
		int firstImageIdx;
		
		if (gallery.getSlideShowParameter().isShuffled())
		{
			this.images = new ArrayList<>(imagesOrdered);
			Collections.shuffle(this.images);
			firstImageIdx = this.images.indexOf(imagesOrdered.get(startingIndex));
		}
		else
		{
			this.images = this.imagesOrdered;
			firstImageIdx = startingIndex;
		}
		
		imageView = new ImageView();
		imageView.setFitWidth(screenSize.getWidth());
		imageView.setFitHeight(screenSize.getHeight());
		imageView.setPreserveRatio(true);
		imageView.setSmooth(true);
		
		mediaView = new MediaView();
		mediaView.setFitWidth(screenSize.getWidth());
		mediaView.setFitHeight(screenSize.getHeight());
		mediaView.setPreserveRatio(true);
		mediaView.setSmooth(true);
		
		parametersPreviousVideo = new VideoParameters();
		parametersPreviousVideo.copyFrom(gallery.getSlideShowParameter().getVideos());
		
		fullImageUpdatingThread = new ImageLoaderDaemon();
		
		//Don't call next() as it also reset autoplay timer resulting in a double call
		autoplay = new Timeline(new KeyFrame(Duration.seconds(1), event -> setCurrent(validIndex(currentImageIdx, 1))));
		autoplay.setCycleCount(Timeline.INDEFINITE);
		setAutoplayDelay(gallery.getSlideShowParameter().getAutoplayDelay());
		
		contentRoot = new StackPane(imageView, mediaView);
		contentRoot.setId("slide_show_content");
		contentRoot.setCursor(Cursor.NONE);
		setScene(new Scene(contentRoot));
		getScene().getStylesheets().add(UIController.STYLESHEET_DEFAULT);
		
		infoZone = new InfoZone();
		infoZone.setVisible(false);
		contentRoot.getChildren().add(infoZone);
		StackPane.setAlignment(infoZone, Pos.TOP_LEFT);
		StackPane.setMargin(infoZone, new Insets(10));
		
		videoControl = new VideoControl(gallery.getSlideShowParameter().getVideos()::copyFrom, this::showNotification);
		videoControl.videoProperty().bind(mediaView.mediaPlayerProperty());
		contentRoot.getChildren().add(videoControl);
		StackPane.setAlignment(videoControl, Pos.BOTTOM_CENTER);
		StackPane.setMargin(videoControl, new Insets(10));
		videoControl.setVisible(false);
		
		notificationLabel = new Label();
		notificationLabel.getStyleClass().add("notification-label");
		notificationLabel.setOpacity(0);
		contentRoot.getChildren().add(notificationLabel);
		StackPane.setAlignment(notificationLabel, Pos.TOP_RIGHT);
		StackPane.setMargin(notificationLabel, new Insets(55, 90, 0, 0));
		notificationLabelEffectTL = new FadeTransition(Duration.seconds(0.2), notificationLabel);
		notificationLabelEffectTL.setDelay(Duration.seconds(0.8));
		notificationLabelEffectTL.setFromValue(1.0);
		notificationLabelEffectTL.setToValue(0);
		
		SlideShowContextMenu contextMenu = new SlideShowContextMenu(this);
		contextMenu.setHideOnEscape(true);
		
		altDown = new SimpleBooleanProperty(false);
		contentRoot.cursorProperty().bind(new ObjectBinding<Cursor>() {{
				bind(altDown, contextMenu.showingProperty());
			}
			@Override protected Cursor computeValue() {
				return altDown.get() || contextMenu.isShowing() ? null : Cursor.NONE;
			}
		});
		
		BooleanBinding videoActuallyVisible =  new BooleanBinding() {{
				bind(mediaView.mediaPlayerProperty());
			}
			@Override protected boolean computeValue() {
				return mediaView.getMediaPlayer() != null;
			}
		};
		
		infoZone.visibleProperty().bind(altDown);
		videoControl.visibleProperty().bind(new BooleanBinding() {{
				bind(altDown, videoActuallyVisible);
			}
			@Override protected boolean computeValue() {
				return altDown.get() && videoActuallyVisible.get();
			}
		});
		
		addEventHandler(KeyEvent.KEY_PRESSED, event ->
		{
			altDown.set(event.isAltDown());
			
			boolean consumed = true;
			
			if (event.getCode() == KeyCode.ESCAPE)
				close();
			else if (event.getCode() == KeyCode.LEFT)
			{
				if (videoActuallyVisible.get())
					videoControl.jump(-0.1);
				else
					previous();
			}
			else if (event.getCode() == KeyCode.RIGHT)
			{
				if (videoActuallyVisible.get())
					videoControl.jump(+0.1);
				else
					next();
			}
			else if (event.getCode() == KeyCode.UP)
			{
				videoControl.changeVolume(+0.05);
			}
			else if (event.getCode() == KeyCode.DOWN)
			{
				videoControl.changeVolume(-0.05);
			}
			else if (event.getCode() == KeyCode.SPACE)
			{
				if (videoActuallyVisible.get())
				{
					videoControl.playPauseAction();
				}
				else
				{
					setAutoPlay(!isAutoPlay());
					contextMenu.updateItems();
				}
			}
			else
				consumed = false;
			
			if (consumed)
				event.consume();
		});
		addEventHandler(KeyEvent.KEY_RELEASED, event -> altDown.set(event.isAltDown()));
		Robot robot = new Robot();
		addEventHandler(MouseEvent.MOUSE_RELEASED, event ->
		{
			if (event.getButton() == MouseButton.SECONDARY)
			{
				double x, y;
				// If the cursor isn't visible, center it
				if (contentRoot.getCursor() == Cursor.NONE)
				{
					x = getWidth() / 2;
					y = getHeight() / 2;
					
					// Calling just Platform.runLater doesn't work...
					// My guess is that robot.mouseMove is called too early when the cursor still isn't visible, and so does nothing
					StrongReference<Runnable> r = new StrongReference<>();
					r.set(() -> {
						Platform.runLater(() -> robot.mouseMove(x, y));
						getScene().removePostLayoutPulseListener(r.get());
					});
					getScene().addPostLayoutPulseListener(r.get());
				}
				else
				{
					x = event.getScreenX();
					y = event.getScreenY();
				}
				
				contextMenu.show(contentRoot, x, y);
			}
		});
		addEventHandler(MouseEvent.MOUSE_PRESSED, event ->
		{
			if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.SECONDARY)
				contextMenu.hide();
		});
		
		addEventHandler(WindowEvent.WINDOW_SHOWN, event -> fullImageUpdatingThread.start());
		addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
			fullImageUpdatingThread.safeStop();
			autoplay.stop();
		});
		
		setFullScreenExitHint("");
		setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
		setFullScreen(true);
		
		setCurrent(firstImageIdx);
		
		if (isAutoPlay())
			autoplay.playFromStart();
	}
	
	public ReadOnlyObjectProperty<Image> currentImageProperty()
	{
		return currentImageProperty;
	}
	
	public Image getCurrentImage()
	{
		return currentImageProperty.get();
	}
	//TODO add "nextPost" (go to next sibling folder
	public void next()
	{
		autoplay.jumpTo(Duration.ZERO);
		setCurrent(validIndex(currentImageIdx, 1));
	}
	
	public void previous()
	{
		autoplay.jumpTo(Duration.ZERO);
		setCurrent(validIndex(currentImageIdx, -1));
	}
	
	public void setAutoPlay(boolean autoplay)
	{
		if (autoplay && !isAutoPlay())
			this.autoplay.playFromStart();
		else if (!autoplay && isAutoPlay())
			this.autoplay.stop();
		
		gallery.getSlideShowParameter().setAutoplay(autoplay);
	}
	
	public boolean isAutoPlay()
	{
		return gallery.getSlideShowParameter().isAutoplay();
	}
	
	public void setShuffled(boolean shuffled)
	{
		if (shuffled == gallery.getSlideShowParameter().isShuffled())
			return;
		
		gallery.getSlideShowParameter().setShuffled(shuffled);
		
		boolean playing = autoplay.getStatus() == javafx.animation.Animation.Status.RUNNING;
		autoplay.stop();
		if (shuffled) // ordered -> shuffled
		{
			List<Image> shuffledImages = new ArrayList<>(imagesOrdered);
			Collections.shuffle(shuffledImages);
			images = shuffledImages;
			setCurrent(0);
		}
		else // shuffled -> ordered
		{
			int index = imagesOrdered.indexOf(images.get(currentImageIdx));
			images = imagesOrdered;
			setCurrent(index);
		}
		if (playing)
			autoplay.playFromStart();
		fullImageUpdatingThread.forceRefresh();
	}
	
	public boolean isShuffled()
	{
		return gallery.getSlideShowParameter().isShuffled();
	}
	
	public boolean isLooped()
	{
		return gallery.getSlideShowParameter().isLooped();
	}
	
	public void setLooped(boolean looped)
	{
		gallery.getSlideShowParameter().setLooped(looped);
	}
	
	public double getAutoplayDelay()
	{
		return gallery.getSlideShowParameter().getAutoplayDelay();
	}
	
	public void setAutoplayDelay(double autoplayDelay)
	{
		assert autoplayDelay > 0;
		
		gallery.getSlideShowParameter().setAutoplayDelay(autoplayDelay);
		boolean playing = autoplay.getStatus() == javafx.animation.Animation.Status.RUNNING;
		autoplay.stop();
		autoplay.setRate(1 / autoplayDelay);
		if (playing)
			autoplay.playFromStart();
	}
	
	int validIndex(int index, int offset)
	{
		assert index >= 0 && index < images.size();
		
		int nbImages = images.size();
		index += offset;
		
		if (index < 0)
		{
			if (gallery.getSlideShowParameter().isLooped())
			{
				while (index < 0)
					index += nbImages;
			}
			else
				index = 0;
		}
		else if (index >= nbImages)
		{
			if (gallery.getSlideShowParameter().isLooped())
				index %= nbImages;
			else
				index = nbImages - 1;
		}
		while (index < 0)
			index += nbImages;
		return index % nbImages;
	}
	
	//TODO add possibility to (un)zoom
	private void setCurrent(int index)
	{
		assert index >= 0 && index < images.size();
		assert Platform.isFxApplicationThread();
		
		
		if (currentImageIdx != NO_CURRENT_IMAGE_INDEX)
		{
			Image previousImage = images.get(currentImageIdx);
			if (previousImage.isActuallyVideo())
			{
				imageCache.getAsyncFXImageVideo(previousImage).getAsFxVideo().stop();
			}
		}
		
		currentImageIdx = index;
		Image currentImage = images.get(currentImageIdx);
		
		FXImageVideoWrapper fxImageVideo = imageCache.getAsyncFXImageVideo(currentImage);
		
		if (fxImageVideo.getProgressProperty().get() >= 1)
		{
			setFXImageVideo(fxImageVideo);
		}
		else
		{
			setThumbnail(imageCache.getThumbnail(currentImage, false));
		}
		infoZone.setImage(currentImage);
		infoZone.updateImageLoadingInfo(fxImageVideo);
		
		currentImageProperty.fireValueChangedEvent();
	}
	
	private void setThumbnail(javafx.scene.image.Image fxImage)
	{
		synchronized (fxImageVideoCurrentlyVisible)
		{
			updateParametersPreviousVideo();
			imageView.setVisible(true);
			imageView.setImage(fxImage);
			mediaView.setVisible(false);
			mediaView.setMediaPlayer(null);
			fxImageVideoCurrentlyVisible.set(null);
		}
	}
	
	private StrongReference<FXImageVideoWrapper> fxImageVideoCurrentlyVisible = new StrongReference<>(null);
	
	private void setFXImageVideo(FXImageVideoWrapper fxImageVideo)
	{
		if (fxImageVideo != fxImageVideoCurrentlyVisible.get())
		{
			synchronized (fxImageVideoCurrentlyVisible)
			{
				updateParametersPreviousVideo();
				
				if (fxImageVideo != fxImageVideoCurrentlyVisible.get())
				{
					if (fxImageVideo.isVideo())
					{
						MediaPlayer video = fxImageVideo.getAsFxVideo();
						video.setCycleCount(Integer.MAX_VALUE);
						if (parametersPreviousVideo.isAutoplay())
							video.play();
						else
							video.stop();
						video.setMute(parametersPreviousVideo.isMute());
						video.setVolume(parametersPreviousVideo.getVolume());
						
						imageView.setVisible(false);
						imageView.setImage(null);
						mediaView.setVisible(true);
						mediaView.setMediaPlayer(video);//FIXME concurrent modification excaption here in Application Tghread (https://bugs.openjdk.org/browse/JDK-8146918)
					}
					else
					{
						imageView.setVisible(true);
						imageView.setImage(fxImageVideo.getAsFxImage());
						mediaView.setVisible(false);
						mediaView.setMediaPlayer(null);
					}
					
					fxImageVideoCurrentlyVisible.set(fxImageVideo);
				}
			}
		}
	}
	
	private void updateParametersPreviousVideo()
	{
		setVideoParametersFromVideo(parametersPreviousVideo, mediaView.getMediaPlayer());
	}
	
	private static void setVideoParametersFromVideo(VideoParameters parameters, MediaPlayer video)
	{
		if (video != null)
		{
			parameters.setAutoplay(List.of(Status.PLAYING, Status.STALLED).contains(video.getStatus()));
			parameters.setMute(video.isMute());
			parameters.setVolume(video.getVolume());
		}
	}
	
	private void showNotification(String text)
	{
		notificationLabel.setText(text);
		notificationLabel.setOpacity(1);
		notificationLabelEffectTL.playFromStart();
	}
	
	private static class InfoZone extends VBox
	{
		private final Text imagePath;
		private final Text imageSize;
		private final VBox imageTags;
		
		public InfoZone()
		{
			getStyleClass().add("info-zone");
			
			imagePath = new Text();
			imagePath.getStyleClass().add("image-path");
			
			imageSize = new Text();
			imagePath.getStyleClass().add("image-size");
			
			imageTags = new VBox();
			imageTags.getStyleClass().add("tag-list");
			
			getChildren().setAll(imagePath, imageSize, imageTags);
		}
		
		public void setImage(Image image)
		{
			imagePath.setText(image.getPath().toString());
			imageTags.getChildren().clear();
			image.getTags().stream().sorted(Comparator.comparing(Tag::getName)).forEachOrdered(tag ->
			{
				Color tagColor = tag.getColor();
				
				Text tagText = new Text(tag.getName());
				tagText.getStyleClass().add("tag");
				if (tagColor != null)
					tagText.setStyle("-fx-fill: " + FXUtils.toRGBA(tagColor) + ";");
				
				imageTags.getChildren().add(tagText);
			});
		}
		
		public void updateImageLoadingInfo(FXImageVideoWrapper fxImageVideo)
		{
			int width = 0;
			int height = 0;
			
			if (fxImageVideo.getProgressProperty().get() >= 1)
			{
				if (fxImageVideo.isVideo())
				{
					width = fxImageVideo.getAsFxVideo().getMedia().getWidth();
					height = fxImageVideo.getAsFxVideo().getMedia().getHeight();
				}
				else
				{
					width = (int) fxImageVideo.getAsFxImage().getWidth();
					height = (int) fxImageVideo.getAsFxImage().getHeight();
				}
			}
			
			String w = width > 0 ? "%d".formatted(width) : "???";
			String h = height > 0 ? "%d".formatted(height) : "???";
			
			imageSize.setText(w + "x" + h);
		}
	}
	
	private static class VideoControl extends HBox
	{
		private static final Map<Status, String> STATUS_STYLE_CLASSES;
		static {
			STATUS_STYLE_CLASSES = Map.of(Status.PLAYING, "video-pause-icon",
			                              Status.READY, "video-play-icon",
			                              Status.PAUSED, "video-play-icon",
			                              Status.STOPPED, "video-play-icon");
		}
		private static final String STATUS_UNKNOWN_STYLE_CLASS = "video-unknow-status-icon";
		
		private static final Map<Double, String> VOLUME_STYLE_CLASSES;
		static {
			VOLUME_STYLE_CLASSES = Map.of(0.0, "volume-off-icon",
			                              0.5, "volume-down-icon",
			                              1.0, "volume-up-icon");
		}
		private static final String VOLUME_MUTE_STYLE_CLASS = "volume-mute-icon";
		
		private static final String SAVE_STYLE_CLASS = "save-icon";
		
		private final SimpleObjectProperty<MediaPlayer> video;
		private final SimpleObjectProperty<Status> videoStatus;
		private final SimpleObjectProperty<Duration> videoDuration;
		private final SimpleObjectProperty<Duration> videoCurrentTime;
		private final SimpleDoubleProperty videoVolume;
		private final SimpleBooleanProperty videoVolumeMute;
		
		private final Button playPause;
		private final FontIcon playPauseIcon;
		private final Label currentTime;
		private final Slider timeline;
		private final TimeLineToolTip timelineTooltip;
		private final Label duration;
		private final Button muteUnmute;
		private final FontIcon volumeIcon;
		private final Slider volume;
		private final Button saveParameters;
		
		private final Consumer<String> showNotificationAction;
		
		private volatile boolean timelineControlledByUser;
		
		public VideoControl(Consumer<VideoParameters> saveParametersAction, Consumer<String> showNotificationAction)
		{
			this.showNotificationAction = showNotificationAction;
			
			video = new SimpleObjectProperty<MediaPlayer>(this, "video", null);
			videoStatus = new SimpleObjectProperty<>(Status.UNKNOWN);
			videoDuration = new SimpleObjectProperty<>(Duration.UNKNOWN);
			videoCurrentTime = new SimpleObjectProperty<>(Duration.UNKNOWN);
			videoVolume = new SimpleDoubleProperty(0);
			videoVolumeMute = new SimpleBooleanProperty(false);
			
			getStyleClass().add("video-control");
			
			playPauseIcon = new FontIcon();
			videoStatus.addListener((obs, oldValue, newValue) -> {
				playPauseIcon.getStyleClass().removeAll(STATUS_STYLE_CLASSES.values());
				playPauseIcon.getStyleClass().remove(STATUS_UNKNOWN_STYLE_CLASS);
				playPauseIcon.getStyleClass().add(STATUS_STYLE_CLASSES.getOrDefault(newValue, STATUS_UNKNOWN_STYLE_CLASS));
			});
			playPause = new Button(null, playPauseIcon);
			playPause.setOnAction(e -> playPauseAction());
			allowClickWithAltDown(playPause);
			
			currentTime = new Label();
			currentTime.getStyleClass().add("current-time");
			currentTime.textProperty().bind(formattedDuration(videoCurrentTime, true));
			
			timeline = new Slider();
			timeline.getStyleClass().add("timeline");
			HBox.setHgrow(timeline, Priority.ALWAYS);
			timeline.maxProperty().bind(new DoubleBinding() {{
					bind(videoDuration);
				}
				@Override protected double computeValue() {
					Duration duration = videoDuration.get();
					return duration.isUnknown() ? 1d : duration.toSeconds();
				}
			});
			timelineControlledByUser = false;
			timelineTooltip = new TimeLineToolTip();
			Platform.runLater(() -> {
				Region track = (Region) timeline.lookup(".track");
				Region thumb = (Region) timeline.lookup(".thumb");
				track.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> timelineControlledByUser = true);
				track.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> timelineControlledByUser = false);
				getScene().addEventFilter(MouseEvent.MOUSE_MOVED, e -> updateTimeLineToolTip(track, thumb, e));
				getScene().addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> updateTimeLineToolTip(track, thumb, e));
				getScene().addEventFilter(MouseEvent.MOUSE_RELEASED, e -> updateTimeLineToolTip(track, thumb, e));
			});
			videoCurrentTime.addListener((obs) -> {
				Platform.runLater(() -> {
					if (!isTimelineControlledByUser()) {
						Duration duration = videoCurrentTime.get();
						timeline.setValue(duration.isUnknown() ? 0d : duration.toSeconds());
					}
				});
			});
			timeline.valueProperty().addListener(obs -> {
				MediaPlayer v = video.get();
				if (v != null && isTimelineControlledByUser())
					v.seek(Duration.seconds(timeline.getValue()));
			});
			
			duration = new Label();
			duration.getStyleClass().add("duration");
			duration.textProperty().bind(formattedDuration(videoDuration, true));
			
			volumeIcon = new FontIcon();
			InvalidationListener updateVolumeIcon = obs -> {
				volumeIcon.getStyleClass().removeAll(VOLUME_STYLE_CLASSES.values());
				volumeIcon.getStyleClass().remove(VOLUME_MUTE_STYLE_CLASS);
				if (videoVolumeMute.get())
					volumeIcon.getStyleClass().add(VOLUME_MUTE_STYLE_CLASS);
				else
					volumeIcon.getStyleClass()
					          .add(VOLUME_STYLE_CLASSES.entrySet()
					                                   .stream()
					                                   .filter(e -> e.getKey() <= videoVolume.get())
					                                   .max(Comparator.comparing(e -> e.getKey()))
					                                   .map(e -> e.getValue())
					                                   .get());
			};
			videoVolume.addListener(updateVolumeIcon);
			videoVolumeMute.addListener(updateVolumeIcon);
			muteUnmute = new Button(null, volumeIcon);
			muteUnmute.getStyleClass().add("mute-unmute");
			muteUnmute.setOnAction(e -> muteUnmuteAction());
			allowClickWithAltDown(muteUnmute);
			
			volume = new Slider();
			volume.getStyleClass().add("volume");
			volume.setMax(1);
			volume.valueProperty().bindBidirectional(videoVolume);
			
			FontIcon saveIcon = new FontIcon();
			saveIcon.getStyleClass().add(SAVE_STYLE_CLASS);
			saveParameters = new Button(null, saveIcon);
			saveParameters.setOnAction(e -> {
				VideoParameters parameters = new VideoParameters();
				setVideoParametersFromVideo(parameters, video.get());
				saveParametersAction.accept(parameters);
			});
			allowClickWithAltDown(saveParameters);
			
			getChildren().setAll(playPause, currentTime, timeline, duration, muteUnmute, volume, saveParameters);
			
			
			
			video.addListener((obs, oldVideo, newVideo) ->
			{
				Platform.runLater(() ->
				{
					videoStatus.unbind();
					videoDuration.unbind();
					videoCurrentTime.unbind();
					if (oldVideo != null) {
						videoVolume.unbindBidirectional(oldVideo.volumeProperty());
						videoVolumeMute.unbindBidirectional(oldVideo.muteProperty());
					}
					
					timelineControlledByUser = false;
					
					if(newVideo != null)
					{
						videoStatus.bind(newVideo.statusProperty());
						videoDuration.bind(newVideo.cycleDurationProperty());
						videoCurrentTime.bind(newVideo.currentTimeProperty());
						videoVolume.set(newVideo.volumeProperty().get());
						videoVolume.bindBidirectional(newVideo.volumeProperty());
						videoVolumeMute.set(newVideo.muteProperty().get());
						videoVolumeMute.bindBidirectional(newVideo.muteProperty());
					}
				});
			});
		}
		
		public final ObjectProperty<MediaPlayer> videoProperty() {
			return video;
		}
		
		public void playPauseAction()
		{
			MediaPlayer video = this.video.get();
			if (video == null)
				return;
			
			switch (videoStatus.get())
			{
				case READY:
				case PAUSED:
				case STOPPED:
					video.play();
					break;
				case PLAYING:
					video.pause();
					break;
				default:
					break;
			}
		}
		
		private void muteUnmuteAction()
		{
			MediaPlayer video = this.video.get();
			if (video == null)
				return;
			
			video.setMute(!videoVolumeMute.get());
		}
		
		public void jump(double coef)
		{
			MediaPlayer video = this.video.get();
			if (video == null)
				return;
			
			double seekPos = video.getCurrentTime().toSeconds() + Math.min(coef * video.getTotalDuration().toSeconds(), 3d);
			seekPos = Math.max(0d, Math.min(seekPos, video.getTotalDuration().toSeconds()));
			Duration seekDuration = Duration.seconds(seekPos);
			
			video.seek(seekDuration);
			showNotificationAction.accept("%s / %s".formatted(formatDuration(seekDuration, false), formatDuration(video.getTotalDuration(), false)));
		}
		
		public void changeVolume(double coef)
		{
			MediaPlayer video = this.video.get();
			if (video == null)
				return;
			
			if (coef > 0d && videoVolumeMute.get()) {
				video.setMute(false);
			}
			else {
				double volume = video.getVolume() + coef;
				volume = Math.max(0d, Math.min(volume, 1d));
				
				video.setVolume(volume);
			}
			
			showNotificationAction.accept("Volume : %.0f%%".formatted(video.getVolume() * 100));
		}
		
		static private void allowClickWithAltDown(ButtonBase button)
		{
			// From com.sun.javafx.scene.control.behavior.ButtonBehavior.mousePressed
			button.addEventHandler(MouseEvent.MOUSE_PRESSED, e ->
			{
				boolean valid = (e.getButton() == MouseButton.PRIMARY
				        && !(e.isMiddleButtonDown() || e.isSecondaryButtonDown() || e.isShiftDown()
				                || e.isControlDown() /* || e.isAltDown() */ || e.isMetaDown()));
				
				if (!button.isArmed() && valid)
				{
					button.arm();
				}
			});
		}
		
		private static final DateTimeFormatter SHORT_DURATION_FORMAT = DateTimeFormatter.ofPattern("mm:ss");
		private static final DateTimeFormatter PADDED_SHORT_DURATION_FORMAT = DateTimeFormatter.ofPattern("   mm:ss");
		private static final DateTimeFormatter LONG_DURATION_FORMAT         = DateTimeFormatter.ofPattern("HH:mm:ss");
		
		private static String formatDuration(Duration duration, boolean fixedWidth)
		{
			if (duration.isUnknown())
				return "--:--";
			
			LocalTime time = LocalTime.ofSecondOfDay((int) duration.toSeconds());
			if (duration.toHours() >= 1d)
				return LONG_DURATION_FORMAT.format(time);
			else if (fixedWidth)
				return PADDED_SHORT_DURATION_FORMAT.format(time);
			else
				return SHORT_DURATION_FORMAT.format(time);
		}
		
		private static StringBinding formattedDuration(SimpleObjectProperty<Duration> durationProperty, boolean fixedWidth)
		{
			return new StringBinding()
			{
				{
					bind(durationProperty);
				}
				@Override
				protected String computeValue()
				{
					Duration duration = durationProperty.get();
					return formatDuration(duration, fixedWidth);
				}
			};
		}
		
		private boolean isTimelineControlledByUser() {
			return timelineControlledByUser || timeline.isValueChanging();
		}
		
		private void updateTimeLineToolTip(Region track, Region thumb, MouseEvent e)
		{
			if (!isVisible() ||
			    isDisabled() ||
			    (
			      !track.localToScreen(track.getBoundsInLocal()).contains(e.getScreenX(), e.getScreenY()) &&
			      !thumb.localToScreen(thumb.getBoundsInLocal()).contains(e.getScreenX(), e.getScreenY()) &&
			      (
			        !isTimelineControlledByUser() ||
			        e.getEventType() == MouseEvent.MOUSE_RELEASED
			      )
			    )
			   )
			{
				timelineTooltip.hide();
				return;
			}
			
			double trackLength = track.getWidth();
			double posInTrackX = track.screenToLocal(e.getScreenX(), e.getScreenY()).getX();
			posInTrackX = Math.max(0d, Math.min(posInTrackX, trackLength));
			double seconds = (timeline.getMax() - timeline.getMin()) * posInTrackX / trackLength;
			timelineTooltip.setText(formatDuration(Duration.seconds(seconds), false));
			
			timelineTooltip.getScene().getRoot().applyCss();
			timelineTooltip.getScene().getRoot().layout();
			timelineTooltip.sizeToScene();
			
			double x = e.getScreenX() - (timelineTooltip.getWidth() / 2);
			double y = localToScreen(getLayoutBounds()).getMinY() - timelineTooltip.getHeight();
			
			if (!timelineTooltip.isShowing())
				timelineTooltip.show(this, x, y);
			else {
				timelineTooltip.setAnchorX(x);
				timelineTooltip.setAnchorY(y);
			}
		}
		
		private static class TimeLineToolTip extends Popup
		{
			private final Label text;
			
			public TimeLineToolTip()
			{
				text = new Label();
				HBox vBox = new HBox(text);
				vBox.getStyleClass().add("timeline-tooltip");
				getContent().add(vBox);
			}
			
			public void setText(String text) {
				this.text.setText(text);
			}
		}
	}
	
	private class CurrentImageProperty extends ReadOnlyObjectPropertyBase<Image>
	{
		@Override
		public Object getBean()
		{
			return SlideShowStage.this;
		}
		
		@Override
		public String getName()
		{
			return "currentImage";
		}
		
		@Override
		public Image get()
		{
			return currentImageIdx == NO_CURRENT_IMAGE_INDEX ? null : images.get(currentImageIdx);
		}
		
		@Override
		public void fireValueChangedEvent()
		{
			super.fireValueChangedEvent();
		}
	}
	
	private class ImageLoaderDaemon extends SafeThread
	{
		private final AtomicBoolean forceRefresh = new AtomicBoolean(false);
		
		public ImageLoaderDaemon()
		{
			super("slide-show-image-loader");
			setDaemon(true);
		}
		
		public void forceRefresh()
		{
			forceRefresh.set(true);
		}
		
		@Override
		public void run()
		{
			List<Image> previousImagesToLoad = List.of();
			List<Image> imagesToLoad = List.of();
			try
			{
				mainLoop:
				while (true)
				{
					SafeThread.checkThreadState();
					
					int current = currentImageIdx;
					imagesToLoad = List.of(images.get(validIndex(current, 0)),
					                       images.get(validIndex(current, 1)),
					                       images.get(validIndex(current, -1)),
					                       images.get(validIndex(current, 2)),
					                       images.get(validIndex(current, 3)));
					
					for (Image image : previousImagesToLoad)
					{
						if (!imagesToLoad.contains(image))
							imageCache.cancelLoadingFXImageVideo(image);
					}
					
					for (Image image : imagesToLoad)
					{
						SafeThread.checkThreadState();
						
						FXImageVideoWrapper fxImageVideo = imageCache.getAsyncFXImageVideo(image);
						
						if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
						{
							previousImagesToLoad = imagesToLoad;
							continue mainLoop;
						}
						
						while (fxImageVideo.getProgressProperty().get() < 1)
						{
							SafeThread.checkThreadState();
							Thread.sleep(100);
							
							if (current != currentImageIdx || forceRefresh.compareAndSet(true, false))
							{
								previousImagesToLoad = imagesToLoad;
								continue mainLoop;
							}
						}
						
						if (fxImageVideo.getProgressProperty().get() >= 1 && image.equals(images.get(currentImageIdx)))
						{
							setFXImageVideo(fxImageVideo);
							infoZone.updateImageLoadingInfo(fxImageVideo);
						}
					}
					
					while (current == currentImageIdx && !forceRefresh.get())
					{
						SafeThread.checkThreadState();
						Thread.sleep(100);
					}
				}
			}
			catch (ThreadStopException | InterruptedException e)
			{
			}
			finally
			{
				for (Image image : previousImagesToLoad)
					imageCache.cancelLoadingFXImageVideo(image);
				for (Image image : imagesToLoad)
					imageCache.cancelLoadingFXImageVideo(image);
			}
		}
	}
}

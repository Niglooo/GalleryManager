package nigloo.gallerymanager.script;

import java.io.IOException;

import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.ui.UIController;
import nigloo.tool.injection.annotation.Inject;

public class ScriptAPI
{
	@Inject
	private UIController uiController;
	@Inject
	private Gallery gallery;

	public Gallery getGallery()
	{
		return gallery;
	}
	
	public void saveGallery() throws IOException
	{
		uiController.saveGallery();
	}
}
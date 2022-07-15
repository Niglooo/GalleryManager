module nigloo.gallerymanager
{
	requires java.desktop;
	requires java.net.http;
	requires java.scripting;
	
	requires javafx.base;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;
	requires javafx.media;
	
	requires com.google.gson;
	requires methanol;
	requires nigloo.tools;
	requires org.apache.logging.log4j;
	requires org.apache.logging.log4j.core;
	requires org.jsoup;
	
	opens nigloo.gallerymanager.model;// to javafx.fxml, com.google.gson, nigloo.tools;
	opens nigloo.gallerymanager.script;
	opens nigloo.gallerymanager.autodownloader;
	opens nigloo.gallerymanager.ui to javafx.graphics, javafx.fxml, nigloo.tools;
	opens nigloo.gallerymanager.ui.dialog to javafx.graphics, javafx.fxml, nigloo.tools;
	
	opens resources.styles; // Oh shit, here we go again
}

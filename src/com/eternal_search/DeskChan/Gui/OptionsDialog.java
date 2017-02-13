package com.eternal_search.DeskChan.Gui;

import com.eternal_search.DeskChan.Core.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class OptionsDialog extends JFrame {
	
	private MainWindow mainWindow;
	private JTabbedPane tabbedPane = new JTabbedPane();
	private JList skinList;
	private Action selectSkinAction = new AbstractAction("Select") {
		@Override
		public void actionPerformed(ActionEvent actionEvent) {
			Object selectValue = skinList.getSelectedValue();
			if (selectValue != null) {
				SkinInfo skinInfo = (SkinInfo) selectValue;
				mainWindow.getCharacterWidget().loadImage(skinInfo.path);
				mainWindow.setDefaultLocation();
			}
		}
	};
	
	OptionsDialog(MainWindow mainWindow) {
		super("DeskChan Options");
		this.mainWindow = mainWindow;
		setMinimumSize(new Dimension(300, 200));
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setContentPane(tabbedPane);
		JPanel appearanceTab = new JPanel(new BorderLayout());
		skinList = new JList(loadSkinList().toArray());
		skinList.setLayoutOrientation(JList.VERTICAL);
		skinList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					selectSkinAction.actionPerformed(null);
				}
			}
		});
		JScrollPane skinListScrollPane = new JScrollPane(skinList);
		appearanceTab.add(skinListScrollPane);
		JButton button = new JButton(selectSkinAction);
		appearanceTab.add(button, BorderLayout.PAGE_END);
		tabbedPane.addTab("Appearance", appearanceTab);
		pack();
	}
	
	private ArrayList<SkinInfo> loadSkinList() {
		ArrayList<SkinInfo> list = new ArrayList<>();
		Path directoryPath = Utils.getResourcePath("characters");
		if (directoryPath != null) {
			try {
				DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directoryPath);
				for (Path skinPath : directoryStream) {
					if (!Files.isDirectory(skinPath)) {
						list.add(new SkinInfo(skinPath));
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		Collections.sort(list);
		return list;
	}
	
	@Override
	public void dispose() {
		mainWindow.optionsDialog = null;
		super.dispose();
	}
	
	class SkinInfo implements Comparable<SkinInfo> {
		
		String name;
		Path path;
		
		SkinInfo(String name, Path path) {
			this.name = name;
			this.path = path;
		}
		
		SkinInfo(Path path) {
			this(path.getFileName().toString(), path);
		}
		
		@Override
		public int compareTo(SkinInfo o) {
			return name.compareTo(o.name);
		}
		
		@Override
		public String toString() {
			return name;
		}
		
	}
	
}
package com.mygame;

/**
 * @author thuph
 * @author Jonathan Gruber */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

import java.awt.color.ColorSpace;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import java.util.function.Function;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import javax.swing.colorchooser.AbstractColorChooserPanel;

import com.jme3.app.SimpleApplication;

import com.jme3.bullet.BulletAppState;

import com.jme3.bullet.collision.shapes.CollisionShape;

import com.jme3.bullet.control.RigidBodyControl;

import com.jme3.bullet.util.CollisionShapeFactory;

import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;

import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;

import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;

import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.SpotLight;

import com.jme3.material.Material;

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import com.jme3.renderer.queue.RenderQueue;

import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;

import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.shadow.SpotLightShadowRenderer;

import com.jme3.system.AppSettings;

import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.PaintMode;

public class Main extends SimpleApplication {
	private static final float GROUND_SIZE = 100;
	private static final float DICE_TRAY_WIDTH = 10;
	private static final float DICE_TRAY_WALL_HEIGHT = 4;
	private static final float DICE_TRAY_WALL_THICKNESS = 0.2f;

	private static final String DICE_GROUP_TYPE_NAME_PREFIX = "D";

	private static final ColorRGBA DIE_COLOR_DEFAULT = ColorRGBA.White;

	private static final int DICE_GROUP_COUNT_MAX = 100;

	private BulletAppState physics;
	private BitmapText hud;
	private CameraView cameraView;
	private InputMode inputMode;
	private InputErrorStatus inputErrorStatus;
	private StringBuilder inputBuffer;
	private DiceGroupType[] diceGroupTypes;
	private Material dieMaterial;
	private DiceGroupType currentDiceGroupType;
	/* How many dice groups to roll. */
	private int diceGroupCount;
	private List<Node> diceGroups;
	private List<DiceGroupRollResult> diceGroupRollResults;
	/* For getting dice-group roll results in simpleUpdate. */
	private float settleTimer;

	public static void main(final String[] args) {
		final AppSettings settings = new AppSettings(true);
		/* Use AwtPanelsContext so that jME renders to Swing. */
		settings.setCustomRenderer(AwtPanelsContext.class);

		final Main app = new Main();
		app.setSettings(settings);
		app.setShowSettings(false);
		/* jME will create an AwtPanelsContext, so no default window. */
		app.start();
	}

	@Override
	public void simpleInitApp() {
		this.physics = new BulletAppState();
		this.stateManager.attach(this.physics);

		this.setCameraView(CameraView.VERTICAL);
		this.flyCam.setEnabled(false);

		/* Intializes this.diceGroupTypes and this.currentDiceGroupType. */
		this.setupDiceGroupTypes();

		final int diceGroupCountDefault = 1;
		this.diceGroupCount = diceGroupCountDefault;
		this.diceGroups = new ArrayList<>();
		this.diceGroupRollResults = new ArrayList<>();

		this.setupInput();
		this.setupLights();
		this.setupDiceTray();
		this.setupHUD();
		this.setupSwingUi();
	}

	@Override
	public void simpleUpdate(final float tpf) {
		this.simpleUpdateImpl(tpf);
		this.updateHud();
	}

	private void simpleUpdateImpl(final float tpf) {
		if (
			this.diceGroups.isEmpty()
			|| !this.diceGroupRollResults.isEmpty()
		) {
			return;
		}

		float vSum = 0, wSum = 0;
		for (final Node diceGroup : this.diceGroups) {
			for (final Spatial die : diceGroup.getChildren()) {
				final RigidBodyControl dieBody =
					die.getControl(RigidBodyControl.class);

				vSum += vectorLengthApprox(dieBody.getLinearVelocity());
				wSum += vectorLengthApprox(dieBody.getAngularVelocity());
			}
		}

		final float vwSumCutoff = 0.1f;
		if (vSum >= vwSumCutoff || wSum >= vwSumCutoff) {
			this.settleTimer = 0;
			return;
		}

		final float settleTimerCutoff = 1;
		this.settleTimer = Math.min(
			this.settleTimer + tpf,
			settleTimerCutoff
		);
		if (this.settleTimer < settleTimerCutoff) {
			return;
		}

		for (final Node diceGroup : this.diceGroups) {
			final List<Spatial> dice = diceGroup.getChildren();
			final DieType[] dieTypes = this.currentDiceGroupType.dieTypes();

			final DieFace[] faces = new DieFace[dice.size()];
			final Iterator<Spatial> diceIter = dice.iterator();
			for (int i = 0; i < faces.length; ++i) {
				final Spatial die = diceIter.next();
				faces[i] = readDieFace(die, dieTypes[i]);
			}

			final DiceGroupRollResult rollResult =
				this.currentDiceGroupType.getRollResultFn().apply(faces);
			this.diceGroupRollResults.add(rollResult);
		}
	}

	private void setupInput() {
		this.inputMode = InputMode.OFF;
		this.inputErrorStatus = InputErrorStatus.OK;
		this.inputBuffer = new StringBuilder();

		/* Actions upon key triggers. */
		final String rollDiceActionName = "ROLL_DICE";
		final String cycleCameraViewActionName = "CYCLE_CAMERA_VIEW";
		final String setDiceGroupTypeActionName = "SET_DICE_GROUP_TYPE";
		final String setDiceGroupCountActionName = "SET_DICE_GROUP_COUNT";
		final String confirmInputActionName = "CONFIRM_INPUT";
		final String cancelInputActionName = "CANCEL_INPUT";

		/* Digit-action names are of the form "DIGIT"d,
		 * where d is a decimal digit. */
		final String digitActionNamePrefix = "DIGIT";
		/* Ideally,
		 * we would test for when the regular "5" key is pressed
		 * together with either "shift" key,
		 * but,
		 * for simplicity,
		 * we unconditionally interpret
		 * both the regular and number-pad "5" key
		 * as "%"
		 * when setting the dice-group type,
		 * which should not cause any problems
		 * since no dice-group type has "5" in its name. */
		final String percentDigitActionNameAffix = "5";

		final ActionListener actionListener = new ActionListener() {
			@Override
			public void onAction(
				final String name,
				final boolean isPressed,
				final float tpf
			) {
				final Main main = Main.this;

				final Runnable resetInputModeAndBuffer = () -> {
					main.inputMode = InputMode.OFF;
					main.inputBuffer.setLength(0);
				};

				if (isPressed) {
					return;
				}

				main.inputErrorStatus = InputErrorStatus.OK;

				switch (main.inputMode) {
					case InputMode.OFF -> {
						if (name.equals(rollDiceActionName)) {
							main.clearDice();
							for (int i = 0; i < main.diceGroupCount; ++i) {
								main.createAndRollDiceGroup();
							}
						} else if (name.equals(cycleCameraViewActionName)) {
							main.setCameraView(main.cameraView.next());
						} else if (name.equals(setDiceGroupTypeActionName)) {
							main.inputMode = InputMode.DICE_GROUP_TYPE;
							main.inputBuffer.append(DICE_GROUP_TYPE_NAME_PREFIX);
						} else if (name.equals(setDiceGroupCountActionName)) {
							main.inputMode = InputMode.DICE_GROUP_COUNT;
						}
					}
					case InputMode.DICE_GROUP_TYPE -> {
						if (name.startsWith(digitActionNamePrefix)) {
							/* DIGIT# -> #. */
							String ch = name.substring(
								digitActionNamePrefix.length()
							);
							if (ch.equals(percentDigitActionNameAffix)) {
								ch = "%";
							}

							main.inputBuffer.append(ch);
						} else if (name.equals(confirmInputActionName)) {
							final String diceGroupTypeName =
								main.inputBuffer.toString();

							Arrays.stream(main.diceGroupTypes)
								.filter(
									type -> diceGroupTypeName.equals(
										type.name()
									)
								)
								.findFirst()
								.ifPresentOrElse(
									type -> main.currentDiceGroupType = type,
									() -> main.inputErrorStatus =
										InputErrorStatus.INVALID_DICE_GROUP_TYPE
								);

							resetInputModeAndBuffer.run();
						} else if (name.equals(cancelInputActionName)) {
							resetInputModeAndBuffer.run();
						}
					}
					case InputMode.DICE_GROUP_COUNT -> {
						if (name.startsWith(digitActionNamePrefix)) {
							/* DIGIT# -> #. */
							main.inputBuffer.append(
								name.substring(
									digitActionNamePrefix.length()
								)
							);
						} else if (name.equals(confirmInputActionName)) {
							Optional<Integer> maybeCount = Optional.empty();
							try {
								maybeCount = Optional.of(
									Integer.parseInt(
										main.inputBuffer.toString()
									)
								);
							} catch (NumberFormatException e) {}

							maybeCount
								.filter(count -> count > 0)
								.ifPresentOrElse(
									count -> {
										main.diceGroupCount =
											Math.min(
												count,
												DICE_GROUP_COUNT_MAX
											);

										if (count > DICE_GROUP_COUNT_MAX) {
											main.inputErrorStatus =
												InputErrorStatus.TOO_BIG_DICE_GROUP_COUNT;
										}
									},
									() -> main.inputErrorStatus =
										InputErrorStatus.INVALID_DICE_GROUP_COUNT
								);

							resetInputModeAndBuffer.run();
						} else if (name.equals(cancelInputActionName)) {
							resetInputModeAndBuffer.run();
						}
					}
				}
			}
		};

		final String[] generalActions = {
			rollDiceActionName,
			cycleCameraViewActionName,
			setDiceGroupTypeActionName,
			setDiceGroupCountActionName,
			confirmInputActionName,
			cancelInputActionName,
		};
		final int[] generalActionKeyCodes = {
			KeyInput.KEY_SPACE,
			KeyInput.KEY_C,
			KeyInput.KEY_T,
			KeyInput.KEY_N,
			KeyInput.KEY_RETURN,
			KeyInput.KEY_ESCAPE,
		};

		for (int i = 0; i < generalActions.length; ++i) {
			final String action = generalActions[i];
			final int keyCode = generalActionKeyCodes[i];

			this.inputManager.addMapping(action, new KeyTrigger(keyCode));
			this.inputManager.addListener(actionListener, action);
		}

		final int[][] digitKeyCodes = {
			{ KeyInput.KEY_0, KeyInput.KEY_NUMPAD0 },
			{ KeyInput.KEY_1, KeyInput.KEY_NUMPAD1 },
			{ KeyInput.KEY_2, KeyInput.KEY_NUMPAD2 },
			{ KeyInput.KEY_3, KeyInput.KEY_NUMPAD3 },
			{ KeyInput.KEY_4, KeyInput.KEY_NUMPAD4 },
			{ KeyInput.KEY_5, KeyInput.KEY_NUMPAD5 },
			{ KeyInput.KEY_6, KeyInput.KEY_NUMPAD6 },
			{ KeyInput.KEY_7, KeyInput.KEY_NUMPAD7 },
			{ KeyInput.KEY_8, KeyInput.KEY_NUMPAD8 },
			{ KeyInput.KEY_9, KeyInput.KEY_NUMPAD9 },
		};

		for (int i = 0; i < digitKeyCodes.length; ++i) {
			final KeyTrigger[] triggers = Arrays.stream(digitKeyCodes[i])
				.mapToObj(KeyTrigger::new)
				.toArray(KeyTrigger[]::new);

			final String digitAction =
				String.format("%s%d", digitActionNamePrefix, i);

			this.inputManager.addMapping(digitAction, triggers);
			this.inputManager.addListener(actionListener, digitAction);
		}
	}

	private static enum InputMode {
		OFF,
		DICE_GROUP_TYPE,
		DICE_GROUP_COUNT;
	}

	private static enum InputErrorStatus {
		OK,
		INVALID_DICE_GROUP_TYPE,
		INVALID_DICE_GROUP_COUNT,
		TOO_BIG_DICE_GROUP_COUNT;
	}

	private void setupLights() {
		final Vector3f sunlightDirection = new Vector3f(1, -1, 1);
		final ColorRGBA sunlightColor = ColorRGBA.White;

		final DirectionalLight sunlight = new DirectionalLight();
		sunlight.setDirection(sunlightDirection);
		sunlight.setColor(sunlightColor);
		this.rootNode.addLight(sunlight);

		final ColorRGBA ambientLightColor = ColorRGBA.White;

		final AmbientLight amb = new AmbientLight();
		amb.setColor(ambientLightColor);
		this.rootNode.addLight(amb);

		final int dlsrShadowMapSize = 2048;
		final int dlsrSplitCount = 4;
		final EdgeFilteringMode dlsrEdgeFilteringMode =
			EdgeFilteringMode.PCFPOISSON;
		final float dlsrShadowIntensity = 0.4f;

		final DirectionalLightShadowRenderer dlsr =
			new DirectionalLightShadowRenderer(
				this.assetManager,
				dlsrShadowMapSize,
				dlsrSplitCount
			);
		dlsr.setLight(sunlight);
		dlsr.setEdgeFilteringMode(dlsrEdgeFilteringMode);
		dlsr.setShadowIntensity(dlsrShadowIntensity);
		dlsr.setEnabledStabilization(true);
		this.viewPort.addProcessor(dlsr);
	}

	private void setupDiceTray() {
		this.setupGround();
		this.setupWalls();
	}

	private void setupGround() {
		final ColorRGBA groundDiffuseColor =
			new ColorRGBA(0.90f, 0.92f, 0.95f, 1);
		final ColorRGBA groundSpecularColor = ColorRGBA.White;
		final float groundShininess = 8f;

		final Material groundMat = new Material(
			this.assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		groundMat.setBoolean("UseMaterialColors", true);
		groundMat.setColor("Diffuse", groundDiffuseColor);
		groundMat.setColor("Specular", groundSpecularColor);
		groundMat.setFloat("Shininess", groundShininess);

		final Geometry ground = new Geometry(
			"ground",
			new Quad(GROUND_SIZE, GROUND_SIZE),
			groundMat
		);
		ground.rotate(-FastMath.HALF_PI, 0, 0);
		ground.setLocalTranslation(-GROUND_SIZE / 2, 0, GROUND_SIZE / 2);
		ground.setShadowMode(RenderQueue.ShadowMode.Receive);

		this.rootNode.attachChild(ground);

		final RigidBodyControl groundBody = new RigidBodyControl(0f);
		ground.addControl(groundBody);
		this.physics.getPhysicsSpace().add(groundBody);
	}

	private void setupWalls() {
		final ColorRGBA wallDiffuseColor = new ColorRGBA(1, 1, 1, 0.5f);
		final ColorRGBA wallSpecularColor = new ColorRGBA(0, 0, 0, 0.5f);
		final float wallShininess = 16f;

		final Material wallMat = new Material(
			this.assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		wallMat.setBoolean("UseMaterialColors", true);
		wallMat.setColor("Diffuse", wallDiffuseColor);
		wallMat.setColor("Specular", wallSpecularColor);
		wallMat.setTransparent(true);
		wallMat.setFloat("Shininess", wallShininess);

		final Vector3f[] wallPositions = {
			/* Near wall. */
			new Vector3f(
				0, DICE_TRAY_WALL_HEIGHT / 2, DICE_TRAY_WIDTH / 2
			),
			/* Far wall. */
			new Vector3f(
				0, DICE_TRAY_WALL_HEIGHT / 2, -DICE_TRAY_WIDTH / 2
			),
			/* Right wall. */
			new Vector3f(
				DICE_TRAY_WIDTH / 2, DICE_TRAY_WALL_HEIGHT / 2, 0
			),
			/* Left wall. */
			new Vector3f(
				-DICE_TRAY_WIDTH / 2, DICE_TRAY_WALL_HEIGHT / 2, 0
			),
		};

		/* Each element: [x, y, z] */
		final float[][] wallDimensions = {
			/* Near wall. */
			{
				DICE_TRAY_WIDTH,
				DICE_TRAY_WALL_HEIGHT,
				DICE_TRAY_WALL_THICKNESS,
			},
			/* Far wall. */
			{
				DICE_TRAY_WIDTH,
				DICE_TRAY_WALL_HEIGHT,
				DICE_TRAY_WALL_THICKNESS,
			},
			/* Right wall. */
			{
				DICE_TRAY_WALL_THICKNESS,
				DICE_TRAY_WALL_HEIGHT,
				DICE_TRAY_WIDTH,
			},
			/* Left wall. */
			{
				DICE_TRAY_WALL_THICKNESS,
				DICE_TRAY_WALL_HEIGHT,
				DICE_TRAY_WIDTH,
			},
		};

		for (int i = 0; i < wallPositions.length; ++i) {
			final Vector3f position = wallPositions[i];
			final float[] wallDim = wallDimensions[i];
			final float xSize = wallDim[0];
			final float ySize = wallDim[1];
			final float zSize = wallDim[2];

			this.addWall(position, xSize, ySize, zSize, wallMat);
		}
	}

	private void addWall(
		final Vector3f pos,
		final float xSize,
		final float ySize,
		final float zSize,
		final Material mat
	) {
		final Geometry wall = new Geometry(
			"wall",
			new Box(xSize / 2, ySize / 2, zSize / 2),
			mat
		);
		wall.setLocalTranslation(pos);
		wall.setShadowMode(RenderQueue.ShadowMode.Receive);
		this.rootNode.attachChild(wall);

		final RigidBodyControl wallBody = new RigidBodyControl(0);
		wall.addControl(wallBody);
		this.physics.getPhysicsSpace().add(wallBody);
	}

	private void setupHUD() {
		this.setDisplayStatView(false);
		this.setDisplayFps(false);

		this.guiNode.detachAllChildren();

		final ColorRGBA hudTextColor = ColorRGBA.Brown;

		this.guiFont = this.assetManager.loadFont(
			"Interface/Fonts/Default.fnt"
		);
		this.hud = new BitmapText(guiFont);
		this.hud.setColor(hudTextColor);

		this.guiNode.attachChild(this.hud);
		this.updateHud();
	}

	private void updateHud() {
		final float hudTextSizeScaleCoeff = 0.001f;
		final float scale = this.cam.getHeight() * hudTextSizeScaleCoeff;
		final float hudTextSizeBase = 24;
		this.hud.setSize(hudTextSizeBase * scale);

		String pre = switch (this.inputMode) {
			case InputMode.OFF -> switch (this.inputErrorStatus) {
				case InputErrorStatus.OK -> "";
				case InputErrorStatus.INVALID_DICE_GROUP_TYPE ->
					"Invalid dice-group type; keeping previous";
				case InputErrorStatus.INVALID_DICE_GROUP_COUNT ->
					"Invalid dice-group count; keeping previous";
				case InputErrorStatus.TOO_BIG_DICE_GROUP_COUNT ->
					String.format(
						"Max dice-group count is %d! Using %d",
						DICE_GROUP_COUNT_MAX, this.diceGroupCount
					);
			};
			case InputMode.DICE_GROUP_TYPE ->
				String.format("Enter dice-group type (ESC=cancel): %s", this.inputBuffer);
			case InputMode.DICE_GROUP_COUNT ->
				String.format("Enter dice-group count (ESC=cancel): %s", this.inputBuffer);
		};
		if (!pre.isEmpty()) {
			pre += System.lineSeparator() + System.lineSeparator();
		}

		String middle = "";
		if (!this.diceGroupRollResults.isEmpty()) {
			final int rollTotal = this.diceGroupRollResults.stream()
				.mapToInt(DiceGroupRollResult::numericValue)
				.sum();

			middle = this.diceGroupRollResults.stream()
				.map(DiceGroupRollResult::displayValue)
				.collect(
					Collectors.joining(
						" ",
						"Rolled: ",
						String.format("%nTotal: %d%n", rollTotal)
					)
				);
		}

		final String controlsSep = "  ";
		final String hudText = String.format(
			"%sCurrent Dice Group: %s x %d%n%sSPACE=roll%sT=type%<sN=count%<sC=camera",
			pre,
			this.currentDiceGroupType.name(),
			this.diceGroupCount,
			middle,
			controlsSep
		);

		this.hud.setLocalTranslation(this.computeHudPosition());
		this.hud.setText(hudText);
	}

	private Vector3f computeHudPosition() {
		return new Vector3f(0, this.cam.getHeight(), 0);
	}

	private void setupSwingUi() {
		final AwtPanelsContext ctx = (AwtPanelsContext)this.getContext();

		final AwtPanel awtPanel = ctx.createPanel(PaintMode.Accelerated);
		final Dimension awtPanelPreferredSize = new Dimension(900, 600);
		awtPanel.setPreferredSize(awtPanelPreferredSize);

		ctx.setInputSource(awtPanel);
		awtPanel.attachTo(true, this.viewPort);
		awtPanel.attachTo(false, this.guiViewPort);

		SwingUtilities.invokeLater(() -> {
			final String windowTitle = "Dice-Rolling Simulator";
			final JFrame frame = new JFrame(windowTitle);
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setLayout(new BorderLayout());

			final String colorPickerToggleButtonClosedText =
				"Color Picker ▼";
			final String colorPickerToggleButtonOpenText =
				"Color Picker ▲";
			final JButton colorPickerToggleButton =
				new JButton(colorPickerToggleButtonClosedText);
			colorPickerToggleButton.setFocusPainted(false);
			colorPickerToggleButton.setBorderPainted(false);
			colorPickerToggleButton.setContentAreaFilled(false);
			colorPickerToggleButton.setOpaque(true);

			final Color colorPickerToggleButtonBackgroundColor =
				new Color(230, 230, 230);
			final Color colorPickerToggleButtonForegroundColor =
				Color.DARK_GRAY;
			colorPickerToggleButton.setBackground(
				colorPickerToggleButtonBackgroundColor
			);
			colorPickerToggleButton.setForeground(
				colorPickerToggleButtonForegroundColor
			);

			final int colorPickerToggleButtonFontSize = 14;
			final Font colorPickerToggleButtonFont = new Font(
				Font.SANS_SERIF,
				Font.PLAIN,
				colorPickerToggleButtonFontSize
			);
			colorPickerToggleButton.setFont(colorPickerToggleButtonFont);

			final int colorPickerToggleButtonBorderWidth = 5;
			final int colorPickerToggleButtonBorderHeight = 10;
			colorPickerToggleButton.setBorder(
				BorderFactory.createEmptyBorder(
					colorPickerToggleButtonBorderWidth,
					colorPickerToggleButtonBorderHeight,
					colorPickerToggleButtonBorderWidth,
					colorPickerToggleButtonBorderHeight
				)
			);

			colorPickerToggleButton.addMouseListener(new MouseAdapter() {
				public void mouseEntered(final MouseEvent evt) {
					final Color mouseEnteredBackgroundColor =
						new Color(210, 210, 210);
					colorPickerToggleButton.setBackground(
						mouseEnteredBackgroundColor
					);
				}

				public void mouseExited(final MouseEvent evt) {
					colorPickerToggleButton.setBackground(
						colorPickerToggleButtonBackgroundColor
					);
				}
			});

			final JColorChooser colorChooser =
				new JColorChooser(colorJmeToAwt(DIE_COLOR_DEFAULT));
			colorChooser.setPreviewPanel(new JPanel());
			colorChooser.getSelectionModel().addChangeListener(e -> {
				final Color awtColor = colorChooser.getColor();
				final ColorRGBA jmeColor = colorAwtToJme(awtColor);

				this.enqueue(() -> this.setDieColor(jmeColor));
			});

			final JPanel colorChooserPanel = new JPanel(new BorderLayout());
			colorChooserPanel.add(colorChooser, BorderLayout.CENTER);
			/* The color chooser is closed initially. */
			colorChooserPanel.setVisible(false);

			colorPickerToggleButton.addActionListener(e -> {
				/* Toggle openness/closedness. */
				final boolean nowOpen = !colorChooserPanel.isVisible();
				colorChooserPanel.setVisible(nowOpen);

				final String label = nowOpen
					? colorPickerToggleButtonOpenText
					: colorPickerToggleButtonClosedText;
				colorPickerToggleButton.setText(label);

				frame.revalidate();
				frame.repaint();
			});

			final JPanel controlBar = new JPanel(new BorderLayout());
			controlBar.add(colorPickerToggleButton, BorderLayout.WEST);

			final JPanel topContainer = new JPanel(new BorderLayout());
			topContainer.add(controlBar, BorderLayout.NORTH);
			topContainer.add(colorChooserPanel, BorderLayout.CENTER);

			frame.add(topContainer, BorderLayout.NORTH);
			frame.add(awtPanel, BorderLayout.CENTER);

			/* Without this statement,
			 * then the window has size zero, essentially. */
			frame.pack();
			/* Center the window on the screen. */
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
		});
	}

	private void setCameraView(final CameraView cameraView) {
		this.cameraView = cameraView;
		this.cam.setLocation(this.cameraView.position());
		this.cam.lookAt(Vector3f.ZERO, this.cameraView.up());
	}

	private void clearDice() {
		for (final Node diceGroup : this.diceGroups) {
			for (final Spatial die : diceGroup.getChildren()) {
				final RigidBodyControl dieBody =
					die.getControl(RigidBodyControl.class);
				this.physics.getPhysicsSpace().remove(dieBody);
			}

			this.rootNode.detachChild(diceGroup);
		}

		this.diceGroups.clear();
		this.diceGroupRollResults.clear();
		this.settleTimer = 0;
	}

	@SuppressWarnings("unchecked")
	private void setupDiceGroupTypes() {
		final int nDieType = 7;

		final int d4TypeIdx = 0;
		final int d6TypeIdx = 1;
		final int d8TypeIdx = 2;
		final int d10TypeIdx = 3;
		final int dPercentTypeIdx = 4;
		final int d12TypeIdx = 5;
		final int d20TypeIdx = 6;

		final String[] typeNames = new String[nDieType];
		typeNames[d4TypeIdx] = "D4";
		typeNames[d6TypeIdx] = "D6";
		typeNames[d8TypeIdx] = "D8";
		typeNames[d10TypeIdx] = "D10";
		typeNames[dPercentTypeIdx] = "D%";
		typeNames[d12TypeIdx] = "D12";
		typeNames[d20TypeIdx] = "D20";

		final float oneThird = 1f / 3;

		final float sqrtOf1Div3 = (float)Math.sqrt(1.0 / 3);
		final float sqrtOf2Div3 = (float)Math.sqrt(2.0 / 3);

		final float d10NormalConst22 = 0.22975292054736118f;
		final float d10NormalConst43 = 0.43701602444882104f;
		final float d10NormalConst60 = 0.6015009550075456f;
		final float d10NormalConst66 = 0.668740304976422f;
		final float d10NormalConst70 = 0.7071067811865476f;
		final float d10NormalConst74 = 0.743496068920369f;

		final float d10CentroidConst20 = 0.2011239660476776f;
		final float d10CentroidConst27 = 0.27639320225002103f;
		final float d10CentroidConst38 = 0.382560516985538f;
		final float d10CentroidConst52 = 0.5265493790649998125f;
		final float d10CentroidConst61 = 0.61899591923633195f;
		final float d10CentroidConst65 = 0.650850826034644425f;

		final float d12NormalConst52 = 0.5257311121191336f;
		final float d12NormalConst85 = 0.85065080835204f;

		final float d12CentroidConst41 = 0.41777457946839342f;
		final float d12CentroidConst67 = 0.675973469215554546f;

		final float d20NormalConst35 = 0.35682208977308993f;
		final float d20NormalConst93 = 0.9341723589627157f;

		final float d20CentroidConst28 = 0.28355026945067996f;
		final float d20CentroidConst45 = 0.4587939734903912f;
		final float d20CentroidConst74 = 0.7423442429410712f;

		final Pair<DieFace, Vector3f>[][] faceCentroidPairArrays =
			(Pair<DieFace, Vector3f>[][])new Pair[nDieType][];
		faceCentroidPairArrays[d4TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace(
						"1",
						1,
						new Vector3f(sqrtOf2Div3, sqrtOf1Div3, 0)
					),
					new Vector3f(sqrtOf2Div3, sqrtOf1Div3, 0)
				),
				new Pair<>(
					new DieFace(
						"2",
						2,
						new Vector3f(-sqrtOf2Div3, sqrtOf1Div3, 0)
					),
					new Vector3f(-sqrtOf2Div3, sqrtOf1Div3, 0)
				),
				new Pair<>(
					new DieFace(
						"3",
						3,
						new Vector3f(0, -sqrtOf1Div3, -sqrtOf2Div3)
					),
					new Vector3f(0, -sqrtOf1Div3, -sqrtOf2Div3)
				),
				new Pair<>(
					new DieFace(
						"4",
						4,
						new Vector3f(0, -sqrtOf1Div3, sqrtOf2Div3)
					),
					new Vector3f(0, -sqrtOf1Div3, sqrtOf2Div3)
				),
			};
		faceCentroidPairArrays[d6TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace("1", 1, Vector3f.UNIT_Y),
					Vector3f.UNIT_Y.mult(sqrtOf1Div3)
				),
				new Pair<>(
					new DieFace("2", 2, Vector3f.UNIT_Z),
					Vector3f.UNIT_Z.mult(sqrtOf1Div3)
				),
				new Pair<>(
					new DieFace("3", 3, Vector3f.UNIT_X),
					Vector3f.UNIT_X.mult(sqrtOf1Div3)
				),
				new Pair<>(
					new DieFace("4", 4, Vector3f.UNIT_X.negate()),
					Vector3f.UNIT_X.mult(-sqrtOf1Div3)
				),
				new Pair<>(
					new DieFace("5", 5, Vector3f.UNIT_Z.negate()),
					Vector3f.UNIT_Z.mult(-sqrtOf1Div3)
				),
				new Pair<>(
					new DieFace("6", 6, Vector3f.UNIT_Y.negate()),
					Vector3f.UNIT_Y.mult(-sqrtOf1Div3)
				),
			};
		faceCentroidPairArrays[d8TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace(
						"1",
						1,
						new Vector3f(sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(oneThird, oneThird, -oneThird)
				),
				new Pair<>(
					new DieFace(
						"2",
						2,
						new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(-oneThird, -oneThird, -oneThird)
				),
				new Pair<>(
					new DieFace(
						"3",
						3,
						new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(-oneThird, oneThird, -oneThird)
				),
				new Pair<>(
					new DieFace(
						"4",
						4,
						new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(oneThird, -oneThird, -oneThird)
				),
				new Pair<>(
					new DieFace(
						"5",
						5,
						new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(-oneThird, oneThird, oneThird)
				),
				new Pair<>(
					new DieFace(
						"6",
						6,
						new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(oneThird, -oneThird, oneThird)
				),
				new Pair<>(
					new DieFace(
						"7",
						7,
						new Vector3f(sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(oneThird, oneThird, oneThird)
				),
				new Pair<>(
					new DieFace(
						"8",
						8,
						new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(-oneThird, -oneThird, oneThird)
				),
			};
		faceCentroidPairArrays[d10TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace(
						"0",
						10,
						new Vector3f(
							d10NormalConst60,
							d10NormalConst66,
							-d10NormalConst43
						)
					),
					new Vector3f(
						d10CentroidConst52,
						d10CentroidConst27,
						-d10CentroidConst38
					)
				),
				new Pair<>(
					new DieFace(
						"1",
						1,
						new Vector3f(
							-d10NormalConst60,
							-d10NormalConst66,
							-d10NormalConst43
						)
					),
					new Vector3f(
						-d10CentroidConst52,
						-d10CentroidConst27,
						-d10CentroidConst38
					)
				),
				new Pair<>(
					new DieFace(
						"2",
						2,
						new Vector3f(
							-d10NormalConst22,
							d10NormalConst66,
							d10NormalConst70
						)
					),
					new Vector3f(
						-d10CentroidConst20,
						d10CentroidConst27,
						d10CentroidConst61
					)
				),
				new Pair<>(
					new DieFace(
						"3",
						3,
						new Vector3f(
							d10NormalConst74,
							-d10NormalConst66,
							0
						)
					),
					new Vector3f(d10CentroidConst65, -d10CentroidConst27, 0)
				),
				new Pair<>(
					new DieFace(
						"4",
						4,
						new Vector3f(
							-d10NormalConst22,
							d10NormalConst66,
							-d10NormalConst70
						)
					),
					new Vector3f(
						-d10CentroidConst20,
						d10CentroidConst27,
						-d10CentroidConst61
					)
				),
				new Pair<>(
					new DieFace(
						"5",
						5,
						new Vector3f(
							d10NormalConst22,
							-d10NormalConst66,
							d10NormalConst70
						)
					),
					new Vector3f(
						d10CentroidConst20,
						-d10CentroidConst27,
						d10CentroidConst61
					)
				),
				new Pair<>(
					new DieFace(
						"6",
						6,
						new Vector3f(
							-d10NormalConst74,
							d10NormalConst66,
							0
						)
					),
					new Vector3f(-d10CentroidConst65, d10CentroidConst27, 0)
				),
				new Pair<>(
					new DieFace(
						"7",
						7,
						new Vector3f(
							d10NormalConst22,
							-d10NormalConst66,
							-d10NormalConst70
						)
					),
					new Vector3f(
						d10CentroidConst20,
						-d10CentroidConst27,
						-d10CentroidConst61
					)
				),
				new Pair<>(
					new DieFace(
						"8",
						8,
						new Vector3f(
							d10NormalConst60,
							d10NormalConst66,
							d10NormalConst43
						)
					),
					new Vector3f(
						d10CentroidConst52,
						d10CentroidConst27,
						d10CentroidConst38
					)
				),
				new Pair<>(
					new DieFace(
						"9",
						9,
						new Vector3f(
							-d10NormalConst60,
							-d10NormalConst66,
							d10NormalConst43
						)
					),
					new Vector3f(
						-d10CentroidConst52,
						-d10CentroidConst27,
						d10CentroidConst38
					)
				),
			};
		faceCentroidPairArrays[d12TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace(
						"1",
						1,
						new Vector3f(d12NormalConst85, d12NormalConst52, 0)
					),
					new Vector3f(d12CentroidConst67, d12CentroidConst41, 0)
				),
				new Pair<>(
					new DieFace(
						"2",
						2,
						new Vector3f(d12NormalConst85, -d12NormalConst52, 0)
					),
					new Vector3f(d12CentroidConst67, -d12CentroidConst41, 0)
				),
				new Pair<>(
					new DieFace(
						"3",
						3,
						new Vector3f(-d12NormalConst52, 0, d12NormalConst85)
					),
					new Vector3f(-d12CentroidConst41, 0, d12CentroidConst67)
				),
				new Pair<>(
					new DieFace(
						"4",
						4,
						new Vector3f(d12NormalConst52, 0, d12NormalConst85)
					),
					new Vector3f(d12CentroidConst41, 0, d12CentroidConst67)
				),
				new Pair<>(
					new DieFace(
						"5",
						5,
						new Vector3f(0, d12NormalConst85, -d12NormalConst52)
					),
					new Vector3f(0, d12CentroidConst67, -d12CentroidConst41)
				),
				new Pair<>(
					new DieFace(
						"6",
						6,
						new Vector3f(0, d12NormalConst85, d12NormalConst52)
					),
					new Vector3f(0, d12CentroidConst67, d12CentroidConst41)
				),
				new Pair<>(
					new DieFace(
						"7",
						7,
						new Vector3f(0, -d12NormalConst85, -d12NormalConst52)
					),
					new Vector3f(0, -d12CentroidConst67, -d12CentroidConst41)
				),
				new Pair<>(
					new DieFace(
						"8",
						8,
						new Vector3f(0, -d12NormalConst85, d12NormalConst52)
					),
					new Vector3f(0, -d12CentroidConst67, d12CentroidConst41)
				),
				new Pair<>(
					new DieFace(
						"9",
						9,
						new Vector3f(-d12NormalConst52, 0, -d12NormalConst85)
					),
					new Vector3f(-d12CentroidConst41, 0, -d12CentroidConst67)
				),
				new Pair<>(
					new DieFace(
						"10",
						10,
						new Vector3f(d12NormalConst52, 0, -d12NormalConst85)
					),
					new Vector3f(d12CentroidConst41, 0, -d12CentroidConst67)
				),
				new Pair<>(
					new DieFace(
						"11",
						11,
						new Vector3f(-d12NormalConst85, d12NormalConst52, 0)
					),
					new Vector3f(-d12CentroidConst67, d12CentroidConst41, 0)
				),
				new Pair<>(
					new DieFace(
						"12",
						12,
						new Vector3f(-d12NormalConst85, -d12NormalConst52, 0)
					),
					new Vector3f(-d12CentroidConst67, -d12CentroidConst41, 0)
				),
			};
		faceCentroidPairArrays[d20TypeIdx] =
			(Pair<DieFace, Vector3f>[])new Pair[] {
				new Pair<>(
					new DieFace(
						"1",
						1,
						new Vector3f(d20NormalConst35, d20NormalConst93, 0)
					),
					new Vector3f(d20CentroidConst28, d20CentroidConst74, 0)
				),
				new Pair<>(
					new DieFace(
						"2",
						2,
						new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(
						-d20CentroidConst45,
						-d20CentroidConst45,
						-d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"3",
						3,
						new Vector3f(d20NormalConst93, 0, d20NormalConst35)
					),
					new Vector3f(d20CentroidConst74, 0, d20CentroidConst28)
				),
				new Pair<>(
					new DieFace(
						"4",
						4,
						new Vector3f(-d20NormalConst93, 0, d20NormalConst35)
					),
					new Vector3f(-d20CentroidConst74, 0, d20CentroidConst28)
				),
				new Pair<>(
					new DieFace(
						"5",
						5,
						new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(
						-d20CentroidConst45,
						d20CentroidConst45,
						-d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"6",
						6,
						new Vector3f(0, -d20NormalConst35, d20NormalConst93)
					),
					new Vector3f(0, -d20CentroidConst28, d20CentroidConst74)
				),
				new Pair<>(
					new DieFace(
						"7",
						7,
						new Vector3f(sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(
						d20CentroidConst45,
						d20CentroidConst45,
						-d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"8",
						8,
						new Vector3f(d20NormalConst35, -d20NormalConst93, 0)
					),
					new Vector3f(d20CentroidConst28, -d20CentroidConst74, 0)
				),
				new Pair<>(
					new DieFace(
						"9",
						9,
						new Vector3f(0, d20NormalConst35, d20NormalConst93)
					),
					new Vector3f(0, d20CentroidConst28, d20CentroidConst74)
				),
				new Pair<>(
					new DieFace(
						"10",
						10,
						new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
					),
					new Vector3f(
						d20CentroidConst45,
						-d20CentroidConst45,
						-d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"11",
						11,
						new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(
						-d20CentroidConst45,
						d20CentroidConst45,
						d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"12",
						12,
						new Vector3f(0, -d20NormalConst35, -d20NormalConst93)
					),
					new Vector3f(0, -d20CentroidConst28, -d20CentroidConst74)
				),
				new Pair<>(
					new DieFace(
						"13",
						13,
						new Vector3f(-d20NormalConst35, d20NormalConst93, 0)
					),
					new Vector3f(-d20CentroidConst28, d20CentroidConst74, 0)
				),
				new Pair<>(
					new DieFace(
						"14",
						14,
						new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(
						-d20CentroidConst45,
						-d20CentroidConst45,
						d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"15",
						15,
						new Vector3f(0, d20NormalConst35, -d20NormalConst93)
					),
					new Vector3f(0, d20CentroidConst28, -d20CentroidConst74)
				),
				new Pair<>(
					new DieFace(
						"16",
						16,
						new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(
						d20CentroidConst45,
						-d20CentroidConst45,
						d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"17",
						17,
						new Vector3f(d20NormalConst93, 0, -d20NormalConst35)
					),
					new Vector3f(d20CentroidConst74, 0, -d20CentroidConst28)
				),
				new Pair<>(
					new DieFace(
						"18",
						18,
						new Vector3f(-d20NormalConst93, 0, -d20NormalConst35)
					),
					new Vector3f(-d20CentroidConst74, 0, -d20CentroidConst28)
				),
				new Pair<>(
					new DieFace(
						"19",
						19,
						new Vector3f(sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
					),
					new Vector3f(
						d20CentroidConst45,
						d20CentroidConst45,
						d20CentroidConst45
					)
				),
				new Pair<>(
					new DieFace(
						"20",
						20,
						new Vector3f(-d20NormalConst35, -d20NormalConst93, 0)
					),
					new Vector3f(-d20CentroidConst28, -d20CentroidConst74, 0)
				),
			};

		final Pair<DieFace, Vector3f>[] dPercentFaceCentroidPairs =
			faceCentroidPairArrays[d10TypeIdx].clone();
		for (int i = 0; i < dPercentFaceCentroidPairs.length; ++i) {
			final Pair<DieFace, Vector3f> oldFaceCentroidPair =
				dPercentFaceCentroidPairs[i];
			final DieFace oldFace = oldFaceCentroidPair.first();
			final Vector3f centroid = oldFaceCentroidPair.second();

			final DieFace newFace = new DieFace(
				String.format("%s0", oldFace.displayValue()),
				oldFace.numericValue() * 10,
				oldFace.normal()
			);

			dPercentFaceCentroidPairs[i] = new Pair<>(newFace, centroid);
		}
		faceCentroidPairArrays[dPercentTypeIdx] = dPercentFaceCentroidPairs;

		final Vector3f[] principleAxes = new Vector3f[nDieType];
		principleAxes[d4TypeIdx] =
			faceCentroidPairArrays[d4TypeIdx][0].first().normal();
		principleAxes[d6TypeIdx] =
			faceCentroidPairArrays[d6TypeIdx][0].first().normal();
		principleAxes[d8TypeIdx] = Vector3f.UNIT_Y;
		principleAxes[d10TypeIdx] = Vector3f.UNIT_Y;
		principleAxes[dPercentTypeIdx] = Vector3f.UNIT_Y;
		principleAxes[d12TypeIdx] =
			faceCentroidPairArrays[d12TypeIdx][0].first().normal();
		principleAxes[d20TypeIdx] =
			faceCentroidPairArrays[d20TypeIdx][0].first().normal();

		final Spatial[] models = new Spatial[nDieType];
		final CollisionShape[] collisionShapes =
			new CollisionShape[nDieType];

		this.dieMaterial = new Material(
			this.assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		this.dieMaterial.setBoolean("UseMaterialColors", true);
		this.setDieColor(DIE_COLOR_DEFAULT);

		for (int i = 0; i < nDieType; ++i) {
			/* D% uses the same model and collision shape as D10. */
			if (i == dPercentTypeIdx) {
				continue;
			}

			final String name = typeNames[i];
			final String modelPath =
				String.format("Models/Dice/%s.obj", name);
			final Spatial model = this.assetManager.loadModel(modelPath);

			model.setMaterial(this.dieMaterial);

			final RenderQueue.ShadowMode dieShadowMode =
				RenderQueue.ShadowMode.CastAndReceive;
			model.setShadowMode(dieShadowMode);

			final CollisionShape collisionShape =
				CollisionShapeFactory.createDynamicMeshShape(model);

			models[i] = model;
			collisionShapes[i] = collisionShape;
		}
		models[dPercentTypeIdx] = models[d10TypeIdx].clone();
				models[dPercentTypeIdx].setMaterial(this.dieMaterial);
		collisionShapes[dPercentTypeIdx] = collisionShapes[d10TypeIdx];

		final BitmapFont dieLabelFont =
			this.assetManager.loadFont("Interface/Fonts/Default.fnt");
		final ColorRGBA dieLabelColor = ColorRGBA.Black;

		/* If the dice has both, say 6 and 9,
		 * then which is which can be ambiguous. */
		final boolean[] alwaysUnambiguousValueOrientations =
			new boolean[nDieType];
		alwaysUnambiguousValueOrientations[d4TypeIdx] = true;
		alwaysUnambiguousValueOrientations[d6TypeIdx] = true;
		alwaysUnambiguousValueOrientations[d8TypeIdx] = true;
		alwaysUnambiguousValueOrientations[d10TypeIdx] = false;
		alwaysUnambiguousValueOrientations[dPercentTypeIdx] = false;
		alwaysUnambiguousValueOrientations[d12TypeIdx] = false;
		alwaysUnambiguousValueOrientations[d20TypeIdx] = false;

		final Node[] prototypes = new Node[nDieType];
		for (int i = 0; i < nDieType; ++i) {
			final Spatial model = models[i];

			final Node prototype = new Node(typeNames[i]);
			prototypes[i] = prototype;

			final float dieScale = 0.5f;
			prototype.scale(dieScale);
			collisionShapes[i].setScale(dieScale);

			prototype.attachChild(model);

			final Vector3f principleAxis = principleAxes[i];
			final Pair<DieFace, Vector3f>[] faceCentroidPairs =
				faceCentroidPairArrays[i];
			final boolean alwaysUnambiguousValueOrientation =
				alwaysUnambiguousValueOrientations[i];

			final Function<String, Spatial> makeLabel = displayValue -> {
				if (!alwaysUnambiguousValueOrientation) {
					/* If displayValue contains only 6's and/or 9's,
					 * then the face's intended orientation/value
					 * is ambiguous,
					 * so we disambiguate by appending a full stop
					 * to the value on the face. */
					final boolean displayValueIsAmbiguous = displayValue
						.codePoints()
						.allMatch(x -> x == '6' || x == '9');
					if (displayValueIsAmbiguous) {
						final String displayValueDisambiguationSuffix = ".";
						displayValue +=
							displayValueDisambiguationSuffix;
					}
				}

				final BitmapText labelText = new BitmapText(dieLabelFont);

				final float labelTextSize = 0.5f;
				labelText.setSize(labelTextSize);
				labelText.setColor(dieLabelColor);
				labelText.setText(displayValue);
				labelText.setLocalTranslation(
					-labelText.getLineWidth() / 2,
					labelText.getLineHeight() / 2,
					0
				);

				final Node label = new Node(
					String.format("Face %s", displayValue)
				);
				label.attachChild(labelText);

				return label;
			};

			final float labelNormalOffset = 1e-4f;

			if (i == d4TypeIdx) {
				for (int j = 0; j < faceCentroidPairs.length; ++j) {
					final Pair<DieFace, Vector3f> fcp = faceCentroidPairs[j];
					final DieFace face = fcp.first();
					final String displayValue = face.displayValue();
					final Vector3f centroidNormal = face.normal();
					final Vector3f centroid = fcp.second();

					final Spatial labelPrototype =
						makeLabel.apply(displayValue);
					int labelPrototypeCloneCounter =
						faceCentroidPairs.length - 2;

					/* Place a label near the vertex centroid
					 * on each adjacent real face of the die. */
					for (int k = 0; k < faceCentroidPairs.length; ++k) {
						/* Skip the entry in faceCentroidPairs
						 * that corresponds to the "face"
						 * whose labels we are currently placing. */
						if (k == j) {
							continue;
						}

						Spatial label = labelPrototype;
						if (labelPrototypeCloneCounter > 0) {
							--labelPrototypeCloneCounter;
							label = labelPrototype.clone();
						}

						/* The inward unit normal of the current real face. */
						final Vector3f inwardNormal =
							faceCentroidPairs[k].first().normal();

						/* The negative
						 * of the vector component of centroidNormal
						 * perpendicular to inwardNormal;
						 * a tangent vector of the current real face
						 * pointing directly away from the current vertex. */
						final Vector3f tangent = centroidNormal
							.project(inwardNormal)
							.subtract(centroidNormal);

						final float tangentCoefficient = 0.5f;
						final Vector3f labelPos = centroid
							.add(tangent.mult(tangentCoefficient))
							.subtract(inwardNormal.mult(labelNormalOffset));
						final Quaternion labelRot =
							new Quaternion().lookAt(
								inwardNormal.negate(),
								centroidNormal
							);
						label.setLocalTranslation(labelPos);
						label.setLocalRotation(labelRot);

						prototype.attachChild(label);
					}
				}
			} else {
				for (final Pair<DieFace, Vector3f> fcp : faceCentroidPairs) {
					final DieFace face = fcp.first();
					final String displayValue = face.displayValue();
					final Vector3f normal = face.normal();
					final Vector3f centroid = fcp.second();

					final Spatial label = makeLabel.apply(displayValue);

					Vector3f rotUp = principleAxis;
					if (isParallel(normal, rotUp)) {
						rotUp = findOrthogonal(rotUp);
					}
					/* Ensure that rotUp and normal do not point
					 * in directions that are somewhat opposite
					 * to one another.
					 * This ensures that the label is oriented
					 * such that the top of the text
					 * is oriented
					 * towards the principal axis of the die. */
					if (rotUp.dot(normal) < 0) {
						rotUp = rotUp.negate();
					}

					final Vector3f labelPos =
						centroid.add(normal.mult(labelNormalOffset));
					final Quaternion labelRot =
						new Quaternion().lookAt(normal, rotUp);
					label.setLocalTranslation(labelPos);
					label.setLocalRotation(labelRot);

					prototype.attachChild(label);
				}
			}
		}

		final DieType[] dieTypes = IntStream.range(0, nDieType)
			.mapToObj(
				i -> new DieType(
					typeNames[i],
					prototypes[i],
					collisionShapes[i],
					Arrays.stream(faceCentroidPairArrays[i])
						.map(Pair::first)
						.toArray(DieFace[]::new)
				)
			)
			.toArray(DieType[]::new);

		final int nDiceGroupType = 8;

		final int d4GroupTypeIdx = 0;
		final int d6GroupTypeIdx = 1;
		final int d8GroupTypeIdx = 2;
		final int d10GroupTypeIdx = 3;
		final int dPercentGroupTypeIdx = 4;
		final int d12GroupTypeIdx = 5;
		final int d20GroupTypeIdx = 6;
		final int d100GroupTypeIdx = 7;

		final Function<DieFace[], DiceGroupRollResult> identityRollResult =
			faces -> {
				final DieFace face = faces[0];
				return new DiceGroupRollResult(
					face.displayValue(),
					face.numericValue()
				);
			};

		this.diceGroupTypes = new DiceGroupType[nDiceGroupType];
		this.diceGroupTypes[d4GroupTypeIdx] = new DiceGroupType(
			"D4",
			new DieType[] { dieTypes[d4TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[d6GroupTypeIdx] = new DiceGroupType(
			"D6",
			new DieType[] { dieTypes[d6TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[d8GroupTypeIdx] = new DiceGroupType(
			"D8",
			new DieType[] { dieTypes[d8TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[d10GroupTypeIdx] = new DiceGroupType(
			"D10",
			new DieType[] { dieTypes[d10TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[dPercentGroupTypeIdx] =
			new DiceGroupType(
				"D%",
				new DieType[] { dieTypes[dPercentGroupTypeIdx] },
				identityRollResult
			);
		this.diceGroupTypes[d12GroupTypeIdx] = new DiceGroupType(
			"D12",
			new DieType[] { dieTypes[d12TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[d20GroupTypeIdx] = new DiceGroupType(
			"D20",
			new DieType[] { dieTypes[d20TypeIdx] },
			identityRollResult
		);
		this.diceGroupTypes[d100GroupTypeIdx] = new DiceGroupType(
			"D100",
			new DieType[] { dieTypes[d10TypeIdx], dieTypes[dPercentTypeIdx] },
			faces -> {
				final DieFace d10Face = faces[0], dPercentFace = faces[1];

				final String d10Dv = d10Face.displayValue();
				final String dPercentDv = dPercentFace.displayValue();

				final String dv =
					dPercentDv.substring(
						0,
						dPercentDv.length() - d10Dv.length()
					) + d10Dv;

				final int d10Nv = d10Face.numericValue();
				final int dPercentNv = dPercentFace.numericValue();

				int nv = d10Nv % 10 + dPercentNv % 100;
				if (nv == 0) {
					nv = 100;
				}

				return new DiceGroupRollResult(dv, nv);
			}
		);

		this.currentDiceGroupType = this.diceGroupTypes[d6GroupTypeIdx];
	}

	private void setDieColor(final ColorRGBA color) {
		this.dieMaterial.setColor("Ambient", color);
		this.dieMaterial.setColor("Diffuse", color);
	}

	private void createAndRollDiceGroup() {
		final DieType[] dieTypes = this.currentDiceGroupType.dieTypes();

		final Node diceGroup = new Node(this.currentDiceGroupType.name());
		this.rootNode.attachChild(diceGroup);
		this.diceGroups.add(diceGroup);

		for (final DieType dieType : dieTypes) {
			/* Create the die. */
			final Spatial die = dieType.prototype().clone();
			diceGroup.attachChild(die);

			final RigidBodyControl dieBody =
				new RigidBodyControl(dieType.collisionShape());
			die.addControl(dieBody);
			this.physics.getPhysicsSpace().add(dieBody);

			/* Roll the die,
			 * by applying a linear and angular impulse to it. */

			/* Randomize the die's initial position and rotation
			 * and the impulses applied to the die,
			 * to ensure randomness for the roll. */
			final float positionXzAbsMax = DICE_TRAY_WIDTH / 4;
			final float positionY = DICE_TRAY_WALL_HEIGHT / 4;
			final Vector3f position = new Vector3f(
				fastRandomFloatClosed(-positionXzAbsMax, positionXzAbsMax),
				positionY,
				fastRandomFloatClosed(-positionXzAbsMax, positionXzAbsMax)
			);

			final Quaternion rotation = new Quaternion(
				/* Tait-Bryan angles. */
				new float[] {
					/* Bank: [0, 2 * pi). */
					fastRandomFloat(0, FastMath.TWO_PI),
					/* Heading: [0, 2 * pi). */
					fastRandomFloat(0, FastMath.TWO_PI),
					/* Elevation: [0, pi). */
					fastRandomFloat(0, FastMath.PI),
				}
			);

			final float linearImpulseXzAbsMax = 1;
			final float linearImpulseY = 6;
			final Vector3f linearImpulse = new Vector3f(
				fastRandomFloatClosed(
					-linearImpulseXzAbsMax,
					linearImpulseXzAbsMax
				),
				linearImpulseY,
				fastRandomFloatClosed(
					-linearImpulseXzAbsMax,
					linearImpulseXzAbsMax
				)
			);

			final float angularImpulseXyzAbsMax = 1;
			final Vector3f angularImpulse = new Vector3f(
				fastRandomFloatClosed(
					-angularImpulseXyzAbsMax,
					angularImpulseXyzAbsMax
				),
				fastRandomFloatClosed(
					-angularImpulseXyzAbsMax,
					angularImpulseXyzAbsMax
				),
				fastRandomFloatClosed(
					-angularImpulseXyzAbsMax,
					angularImpulseXyzAbsMax
				)
			);

			dieBody.setPhysicsLocation(position);
			dieBody.setPhysicsRotation(rotation);

			dieBody.applyImpulse(linearImpulse, Vector3f.ZERO);
			dieBody.applyTorqueImpulse(angularImpulse);
		}
	}

	private static float vectorLengthApprox(final Vector3f v) {
		return Math.abs(v.getX())
			+ Math.abs(v.getY())
			+ Math.abs(v.getZ());
	}

	private static DieFace readDieFace(
		final Spatial die,
		final DieType dieType
	) {
		/* If the "most upward" face of the die were exactly horizontal,
		 * then its outward unit normal would currently be (0, 1, 0).
		 * Therefore,
		 * (0, 1, 0) then must be the result of applying the die's rotation
		 * to the original outward unit normal
		 * of the currently "most upward" face of the die.
		 * Therefore,
		 * we can obtain that original normal
		 * by applying the inverse of the die's rotation to (0, 1, 0).
		 *
		 * Of course,
		 * the "most upward" face of the die
		 * may not be exactly horizontal,
		 * so we determine which face is "most upward"
		 * by determining which original outward unit normal
		 * of any of the faces
		 * coincides the most in direction
		 * with the result of applying the inverse of the die's rotation
		 * to (0, 1, 0).
		 *
		 * Now,
		 * for any nonzero vector v and unit vector u,
		 * v . u = |v| * cos a,
		 * where a is the angle between v and u.
		 * Thus,
		 * for any two unit vectors u1 and u2,
		 * v . u1 > v . u2
		 * <-> |v| * cos a1 > |v| * cos a2
		 * <-> cos a1 > cos a2
		 * <-> a1 < a2,
		 * where a1 and a2 are the angles
		 * of v with u1 and u2, respectively.
		 * Thus,
		 * the direction of v is closer to that of u1 than to that of u2
		 * if and only if v . u1 > v . u2.
		 *
		 * Therefore,
		 * we determine which face is "most upward"
		 * by determining for which face
		 * the dot product,
		 * of its original outward unit normal
		 * with the result of applying the inverse of the die's rotation
		 * to (0, 1, 0),
		 * is greatest. */
		final RigidBodyControl dieBody =
			die.getControl(RigidBodyControl.class);
		final Quaternion rotation = dieBody.getPhysicsRotation();
		final Vector3f up = Vector3f.UNIT_Y;
		final Vector3f upFaceOriginalNormal = rotation.inverse().mult(up);

		DieFace bestFace = null;
		float bestDot = Float.NEGATIVE_INFINITY;
		for (final DieFace face : dieType.faces()) {
			final float dot = upFaceOriginalNormal.dot(face.normal());
			if (dot > bestDot) {
				bestDot = dot;
				bestFace = face;
			}
		}

		return bestFace;
	}

	private static record DiceGroupType(
		String name,
		DieType[] dieTypes,
		Function<DieFace[], DiceGroupRollResult> getRollResultFn
	) {}

	private static record DiceGroupRollResult(
		String displayValue,
		int numericValue
	) {}

	private static record DieType(
		String name,
		Spatial prototype,
		CollisionShape collisionShape,
		DieFace[] faces
	) {}

	private static record DieFace(
		/* The value on the face. */
		String displayValue,
		/* The numeric value of the face,
		 * used for computing the total roll result. */
		int numericValue,
		/* The face's outward unit normal. */
		Vector3f normal
	) {}

	private static enum CameraView {
		VERTICAL,
		DIAGONAL,
		HORIZONTAL;

		public Vector3f position() {
			return switch (this) {
				case VERTICAL -> new Vector3f(
					0,
					DICE_TRAY_WIDTH + 1.5f * DICE_TRAY_WALL_HEIGHT,
					0
				);
				case DIAGONAL -> new Vector3f(
					DICE_TRAY_WIDTH / 2 + 1,
					2 * DICE_TRAY_WALL_HEIGHT + 1,
					DICE_TRAY_WIDTH / 2 + 1
				);
				case HORIZONTAL -> new Vector3f(
					DICE_TRAY_WIDTH / 2 + 1,
					2 * DICE_TRAY_WALL_HEIGHT + 1,
					0
				);
			};
		}

		public Vector3f up() {
			return switch (this) {
				case VERTICAL -> Vector3f.UNIT_Z;
				case DIAGONAL -> Vector3f.UNIT_Y;
				case HORIZONTAL -> Vector3f.UNIT_X.negate();
			};
		}

		public CameraView next() {
			return switch (this) {
				case VERTICAL -> DIAGONAL;
				case DIAGONAL -> HORIZONTAL;
				case HORIZONTAL -> VERTICAL;
			};
		}
	}

	/* Return a random number chosen uniformly at random
	 * from the range [origin, bound). */
	private static float fastRandomFloat(
		final float origin,
		final float bound
	) {
		if (
			Float.NEGATIVE_INFINITY == origin
			|| origin >= bound
			|| bound == Float.POSITIVE_INFINITY
		) {
			throw new IllegalArgumentException(
				"invalid random-float range"
			);
		}

		/* Let a = origin, b = bound, and x this function's return value.
		 * We want a <= x < b.
		 * a <= x < b
		 * iff 0 <= x - a < b - a
		 * iff 0 <= (x - a) / (b - a) < 1.
		 * Let y = (x - a) / (b - a).
		 * Rearranging,
		 * we have x = a + (b - a) * y.
		 * 0 <= y = (x - a) / (b - a) < 1
		 * iff a <= x < b.
		 * Therefore,
		 * if we select y to be
		 * the return value of FastMath.nextRandomFloat(),
		 * which is chosen uniformly at random from the range [0, 1),
		 * we chose x uniformly at random from [a, b). */
		final float a = origin, b = bound;
		final float y = FastMath.nextRandomFloat();
		final float x = Math.fma(y, b - a, a);

		return x;
	}

	/* Return a random number chosen uniformly at random
	 * from the range [origin, bound] */
	private static float fastRandomFloatClosed(
		final float origin,
		final float bound
	) {
		/* Let a = origin and b = bound.
		 * For any float x, x <= b iff x < nextUp(b).
		 * Therefore, [a, b] = [a, nextUp(b)).
		 * Let x be this function's return value.
		 * Then,
		 * by definition of the function fastRandomFloat.
		 * x is chosen uniformly at random
		 * from the range [a, nextUp(b)) = [a, b]. */
		return fastRandomFloat(origin, Math.nextUp(bound));
	}

	private static boolean isParallel(final Vector3f a, final Vector3f b) {
		final Vector3f aProj = a.project(b);
		final float tolerance = 0;
		return a.isSimilar(aProj, tolerance);
	}

	private static Vector3f findOrthogonal(final Vector3f a) {
		final Vector3f[] crossOperands = {
			Vector3f.UNIT_X,
			Vector3f.UNIT_Y,
			Vector3f.UNIT_Z,
		};

		for (final Vector3f b : crossOperands) {
			final Vector3f c = a.cross(b);
			if (!c.isSimilar(Vector3f.ZERO, 0)) {
				return c;
			}
		}

		return Vector3f.ZERO;
	}

	private static ColorRGBA colorAwtToJme(final Color x) {
		final ColorSpace colorSpace = ColorSpace.getInstance(
			ColorSpace.CS_sRGB
		);
		final float[] rgba = x.getComponents(colorSpace, null);
		final float r = rgba[0];
		final float g = rgba[1];
		final float b = rgba[2];
		final float a = rgba[3];
		/* jME colors are in the Linear color space by default. */
		return new ColorRGBA().setAsSrgb(r, g, b, a);
	}

	private static Color colorJmeToAwt(final ColorRGBA x) {
		/* jME colors are in the Linear color space by default. */
		final float[] rgba = x.getAsSrgb().getColorArray();
		final float r = rgba[0];
		final float g = rgba[1];
		final float b = rgba[2];
		final float a = rgba[3];
		return new Color(r, g, b, a);
	}
}

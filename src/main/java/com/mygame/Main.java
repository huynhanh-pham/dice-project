package com.mygame;

/**
 * @author thuph
 * @author Jonathan Gruber */
import com.jme3.app.SimpleApplication;

import com.jme3.bullet.BulletAppState;

import com.jme3.bullet.collision.shapes.CollisionShape;

import com.jme3.bullet.control.RigidBodyControl;

import com.jme3.bullet.util.CollisionShapeFactory;

import com.jme3.font.BitmapText;

import com.jme3.input.KeyInput;

import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;

import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;

import com.jme3.material.Material;

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import com.jme3.renderer.queue.RenderQueue;

import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;

import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;

import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.SpotLightShadowRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main extends SimpleApplication {
	private static final float GROUND_SIZE = 20;
	private static final float DICE_TRAY_WIDTH = 6;
	private static final float DICE_TRAY_WALL_HEIGHT = 3.5f;
	private static final float DICE_TRAY_WALL_THICKNESS = 0.2f;

	private static final String DIE_TYPE_NAME_PREFIX = "D";

	private static final int DIE_COUNT_MAX = 100;

	private BulletAppState physics;
	private BitmapText hud;
	private CameraView cameraView;
	private InputMode inputMode;
	private InputErrorStatus inputErrorStatus;
	private StringBuilder inputBuffer;
	private DieType[] dieTypes;
	private DieType currentDieType;
	/* How many dice to roll. */
	private int dieCount;
	private List<Spatial> dice;
	private List<DieFace> dieRollResults;
	/* For getting die-roll results in simpleUpdate. */
	private float settleTimer;

	public static void main(final String[] args) {
		final Main app = new Main();
		app.setShowSettings(false);
		app.start();
	}

	@Override
	public void simpleInitApp() {
		this.physics = new BulletAppState();
		this.stateManager.attach(this.physics);

		this.setCameraView(CameraView.VERTICAL);
		this.flyCam.setEnabled(false);

		/* Intializes this.dieTypes and this.currentDieType. */
		this.initDieTypes();

		final int dieCountDefault = 1;
		this.dieCount = dieCountDefault;
		this.dice = new ArrayList<>();
		this.dieRollResults = new ArrayList<>();

		this.setupInput();
		this.setupLights();
		this.setupDiceTray();
		this.setupHUD();
	}

	@Override
	public void simpleUpdate(final float tpf) {
		this.simpleUpdateImpl(tpf);
		this.updateHudText();
	}

	private void simpleUpdateImpl(final float tpf) {
		if (this.dice.isEmpty() || !this.dieRollResults.isEmpty()) {
			return;
		}

		float vSum = 0, wSum = 0;
		for (final Spatial die : this.dice) {
			final RigidBodyControl dieBody =
				die.getControl(RigidBodyControl.class);

			vSum += vectorLengthApprox(dieBody.getLinearVelocity());
			wSum += vectorLengthApprox(dieBody.getAngularVelocity());
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

		for (final Spatial die : this.dice) {
			this.dieRollResults.add(this.readDieFace(die));
		}
	}

	private void setupLights() {
		final Vector3f sunlightDirection = new Vector3f(0.1f, -0.1f, 0.1f);
		final ColorRGBA sunlightColor = ColorRGBA.White;

		final DirectionalLight sunlight = new DirectionalLight();
		sunlight.setDirection(sunlightDirection);
		sunlight.setColor(sunlightColor);
		this.rootNode.addLight(sunlight);

		final ColorRGBA ambientLightColor = ColorRGBA.White;

		final AmbientLight amb = new AmbientLight();
		amb.setColor(ambientLightColor);
		this.rootNode.addLight(amb);

		final int dlsrShadowMapSize = 1024;
		final int dlsrSplitCount = 3;

		final DirectionalLightShadowRenderer dlsr =
			new DirectionalLightShadowRenderer(
				assetManager,
				dlsrShadowMapSize,
				dlsrSplitCount
			);
		dlsr.setLight(sunlight);
		this.viewPort.addProcessor(dlsr);
	}

	private void setupDiceTray() {
		this.setupGround();
		this.setupWalls();
	}

	private void setupGround() {
		final Geometry ground =
			new Geometry("ground", new Quad(GROUND_SIZE, GROUND_SIZE));
		ground.rotate(-FastMath.HALF_PI, 0, 0);
		ground.setLocalTranslation(-GROUND_SIZE / 2, 0, GROUND_SIZE / 2);

		final ColorRGBA groundDiffuseColor =
			new ColorRGBA(0.90f, 0.92f, 0.95f, 1);
		final ColorRGBA groundSpecularColor = ColorRGBA.White;
		final float groundShininess = 8f;
		final RenderQueue.ShadowMode groundShadowMode =
			RenderQueue.ShadowMode.Receive;

		final Material groundMat = new Material(
			this.assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		groundMat.setBoolean("UseMaterialColors", true);
		groundMat.setColor("Diffuse", groundDiffuseColor);
		groundMat.setColor("Specular", groundSpecularColor);
		groundMat.setFloat("Shininess", groundShininess);
		ground.setMaterial(groundMat);
		ground.setShadowMode(groundShadowMode);

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
			assetManager,
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
			new Box(xSize / 2f, ySize / 2f, zSize / 2f)
		);
		wall.setMaterial(mat);
		wall.setLocalTranslation(pos);
		wall.setShadowMode(RenderQueue.ShadowMode.Receive);
		this.rootNode.attachChild(wall);

		RigidBodyControl wallBody = new RigidBodyControl(0f);
		wall.addControl(wallBody);
		this.physics.getPhysicsSpace().add(wallBody);
	}

	private void setupHUD() {
		this.setDisplayStatView(false); 
		this.setDisplayFps(false);

		this.guiNode.detachAllChildren();

		final float hudTextSize = 24;
		final ColorRGBA hudTextColor = ColorRGBA.Brown;

		this.guiFont =
			this.assetManager.loadFont("Interface/Fonts/Default.fnt");
		this.hud = new BitmapText(guiFont);
		this.hud.setSize(hudTextSize);
		this.hud.setLocalTranslation(this.getHudPosition());
		this.hud.setColor(hudTextColor);
		this.updateHudText();
		this.guiNode.attachChild(this.hud);
	}

	private Vector3f getHudPosition() {
		return new Vector3f(15, this.cam.getHeight() - 15, 0);
	}

	private void updateHudText() {
		String pre = switch (this.inputMode) {
			case InputMode.OFF -> switch (this.inputErrorStatus) {
				case InputErrorStatus.OK -> "";
				case InputErrorStatus.INVALID_DIE_TYPE ->
					"Invalid die type; keeping previous";
				case InputErrorStatus.INVALID_DIE_COUNT ->
					"Invalid die count; keeping previous";
				case InputErrorStatus.TOO_BIG_DIE_COUNT ->
					String.format(
						"Max die count is %d! Using %d",
						DIE_COUNT_MAX, this.dieCount
					);
			};
			case InputMode.DIE_TYPE ->
				String.format("Enter die type: %s", this.inputBuffer);
			case InputMode.DIE_COUNT ->
				String.format("Enter die count: %s", this.inputBuffer);
		};
		if (!pre.isEmpty()) {
			pre += System.lineSeparator() + System.lineSeparator();
		}

		String middle = "";
		if (!this.dieRollResults.isEmpty()) {
			final int rollTotal = this.dieRollResults.stream()
				.mapToInt(DieFace::totalValue)
				.sum();

			middle = this.dieRollResults.stream()
				.map(DieFace::displayValue)
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
			"%sCurrent Die: %s x%d%n%sSPACE=roll%sT=set type%<sN=set count%<sC=camera",
			pre,
			this.currentDieType.name(),
			this.dieCount,
			middle,
			controlsSep
		);
		this.hud.setText(hudText);
	}

	private void setupInput() {
		this.inputMode = InputMode.OFF;
		this.inputErrorStatus = InputErrorStatus.OK;
		this.inputBuffer = new StringBuilder();

		/* Actions upon key triggers. */
		final String rollDiceActionName = "ROLL_DICE";
		final String cycleCameraViewActionName = "CYCLE_CAMERA_VIEW";
		final String setDieTypeActionName = "SET_DIE_TYPE";
		final String setDieCountActionName = "SET_DIE_COUNT";
		final String confirmInputActionName = "CONFIRM_INPUT";

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
		 * when setting the die type,
		 * which should not cause any problems
		 * since no die type has "5" in its name. */
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
							for (int i = 0; i < main.dieCount; ++i) {
								main.createAndRollDie();
							}
						} else if (name.equals(cycleCameraViewActionName)) {
							main.setCameraView(main.cameraView.next());
						} else if (name.equals(setDieTypeActionName)) {
							main.inputMode = InputMode.DIE_TYPE;
							main.inputBuffer.append(DIE_TYPE_NAME_PREFIX);
						} else if (name.equals(setDieCountActionName)) {
							main.inputMode = InputMode.DIE_COUNT;
						}
					}
					case InputMode.DIE_TYPE -> {
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
							final String dieTypeName =
								main.inputBuffer.toString();

							Arrays.stream(main.dieTypes)
								.filter(
									type -> dieTypeName.equals(type.name())
								)
								.findFirst()
								.ifPresentOrElse(
									type -> main.currentDieType = type,
									() -> main.inputErrorStatus =
										InputErrorStatus.INVALID_DIE_TYPE
								);

							resetInputModeAndBuffer.run();
						}
					}
					case InputMode.DIE_COUNT -> {
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
										main.dieCount =
											Math.min(count, DIE_COUNT_MAX);

										if (count > DIE_COUNT_MAX) {
											main.inputErrorStatus =
												InputErrorStatus.TOO_BIG_DIE_COUNT;
										}
									},
									() -> main.inputErrorStatus =
										InputErrorStatus.INVALID_DIE_COUNT
								);

							resetInputModeAndBuffer.run();
						}
					}
				}
			}
		};

		final String[] generalActions = {
			rollDiceActionName,
			cycleCameraViewActionName,
			setDieTypeActionName,
			setDieCountActionName,
			confirmInputActionName,
		};
		final int[] generalActionKeyCodes = {
			KeyInput.KEY_SPACE,
			KeyInput.KEY_C,
			KeyInput.KEY_T,
			KeyInput.KEY_N,
			KeyInput.KEY_RETURN,
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
		DIE_TYPE,
		DIE_COUNT;
	}

	private static enum InputErrorStatus {
		OK,
		INVALID_DIE_TYPE,
		INVALID_DIE_COUNT,
		TOO_BIG_DIE_COUNT;
	}

	private void setCameraView(final CameraView cameraView) {
		this.cameraView = cameraView;
		this.cam.setLocation(this.cameraView.position());
		this.cam.lookAt(Vector3f.ZERO, this.cameraView.up());
	}

	private void clearDice() {
		for (final Spatial die : this.dice) {
			final RigidBodyControl dieBody =
				die.getControl(RigidBodyControl.class);

			this.physics.getPhysicsSpace().remove(dieBody);
			this.rootNode.detachChild(die);
		}
		this.dice.clear();

		this.dieRollResults.clear();
		this.settleTimer = 0;
	}

	private void initDieTypes() {
		final int d6Idx = 1, d10Idx = 3, dPercentIdx = 4;

		final String[] names = {
			"D4",
			"D6",
			"D8",
			"D10",
			"D%",
			"D12",
			"D20",
		};

		final Spatial[] prototypes = new Spatial[names.length];
		final CollisionShape[] collisionShapes =
			new CollisionShape[names.length];

		for (int i = 0; i < names.length; ++i) {
			/* D% uses the same model and collision shape as D10. */
			if (i == dPercentIdx) {
				continue;
			}

			final String name = names[i];
			final String modelPath =
				String.format("Models/Dice/%s.obj", name);
			final Spatial model = this.assetManager.loadModel(modelPath);

			final float modelScale = 0.5f;
			model.scale(modelScale);

			final CollisionShape collisionShape =
				CollisionShapeFactory.createDynamicMeshShape(model);

			prototypes[i] = model;
			collisionShapes[i] = collisionShape;
		}
		prototypes[dPercentIdx] = prototypes[d10Idx].clone();
		collisionShapes[dPercentIdx] = collisionShapes[d10Idx];

		final float sqrtOf1Div3 = (float)Math.sqrt(1.0 / 3);
		final float sqrtOf2Div3 = (float)Math.sqrt(2.0 / 3);

		final float d10NormalConst22 = 0.22975292054736118f;
		final float d10NormalConst43 = 0.43701602444882104f;
		final float d10NormalConst60 = 0.6015009550075456f;
		final float d10NormalConst66 = 0.668740304976422f;
		final float d10NormalConst70 = 0.7071067811865476f;
		final float d10NormalConst74 = 0.743496068920369f;

		final float d12NormalConstSmall = 0.5257311121191336f;
		final float d12NormalConstBig = 0.85065080835204f;

		final float d20NormalConstSmall = 0.35682208977308993f;
		final float d20NormalConstBig = 0.9341723589627157f;

		final DieFace[][] faceArrays = {
			/* D4. */
			{
				new DieFace(
					"1",
					1,
					new Vector3f(sqrtOf2Div3, sqrtOf1Div3, 0)
				),
				new DieFace(
					"2",
					2,
					new Vector3f(-sqrtOf2Div3, sqrtOf1Div3, 0)
				),
				new DieFace(
					"3",
					3,
					new Vector3f(0, -sqrtOf1Div3, -sqrtOf2Div3)
				),
				new DieFace(
					"4",
					4,
					new Vector3f(0, -sqrtOf1Div3, sqrtOf2Div3)
				),
			},
			/* D6. */
			{
				new DieFace("1", 1, Vector3f.UNIT_Y),
				new DieFace("2", 2, Vector3f.UNIT_Z),
				new DieFace("3", 3, Vector3f.UNIT_X),
				new DieFace("4", 4, Vector3f.UNIT_X.negate()),
				new DieFace("5", 5, Vector3f.UNIT_Z.negate()),
				new DieFace("6", 6, Vector3f.UNIT_Y.negate()),
			},
			/* D8. */
			{
				new DieFace(
					"1",
					1,
					new Vector3f(sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"2",
					2,
					new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"3",
					3,
					new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"4",
					4,
					new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"5",
					5,
					new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"6",
					6,
					new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"7",
					7,
					new Vector3f(sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"8",
					8,
					new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
				),
			},
			/* D10. */
			{
				new DieFace(
					"0",
					10,
					new Vector3f(
						d10NormalConst60,
						d10NormalConst66,
						-d10NormalConst43
					)
				),
				new DieFace(
					"1",
					1,
					new Vector3f(
						-d10NormalConst60,
						-d10NormalConst66,
						-d10NormalConst43
					)
				),
				new DieFace(
					"2",
					2,
					new Vector3f(
						-d10NormalConst22,
						d10NormalConst66,
						d10NormalConst70
					)
				),
				new DieFace(
					"3",
					3,
					new Vector3f(
						d10NormalConst74,
						-d10NormalConst66,
						0
					)
				),
				new DieFace(
					"4",
					4,
					new Vector3f(
						-d10NormalConst22,
						d10NormalConst66,
						-d10NormalConst70
					)
				),
				new DieFace(
					"5",
					5,
					new Vector3f(
						d10NormalConst22,
						-d10NormalConst66,
						d10NormalConst70
					)
				),
				new DieFace(
					"6",
					6,
					new Vector3f(
						-d10NormalConst74,
						d10NormalConst66,
						0
					)
				),
				new DieFace(
					"7",
					7,
					new Vector3f(
						d10NormalConst22,
						-d10NormalConst66,
						-d10NormalConst70
					)
				),
				new DieFace(
					"8",
					8,
					new Vector3f(
						d10NormalConst60,
						d10NormalConst66,
						d10NormalConst43
					)
				),
				new DieFace(
					"9",
					9,
					new Vector3f(
						-d10NormalConst60,
						-d10NormalConst66,
						d10NormalConst43
					)
				),
			},
			/* D%: see below. */
			null,
			/* D12. */
			{
				new DieFace(
					"1",
					1,
					new Vector3f(d12NormalConstBig, d12NormalConstSmall, 0)
				),
				new DieFace(
					"2",
					2,
					new Vector3f(d12NormalConstBig, -d12NormalConstSmall, 0)
				),
				new DieFace(
					"3",
					3,
					new Vector3f(-d12NormalConstSmall, 0, d12NormalConstBig)
				),
				new DieFace(
					"4",
					4,
					new Vector3f(d12NormalConstSmall, 0, d12NormalConstBig)
				),
				new DieFace(
					"5",
					5,
					new Vector3f(0, d12NormalConstBig, -d12NormalConstSmall)
				),
				new DieFace(
					"6",
					6,
					new Vector3f(0, d12NormalConstBig, d12NormalConstSmall)
				),
				new DieFace(
					"7",
					7,
					new Vector3f(0, -d12NormalConstBig, -d12NormalConstSmall)
				),
				new DieFace(
					"8",
					8,
					new Vector3f(0, -d12NormalConstBig, d12NormalConstSmall)
				),
				new DieFace(
					"9",
					9,
					new Vector3f(-d12NormalConstSmall, 0, -d12NormalConstBig)
				),
				new DieFace(
					"10",
					10,
					new Vector3f(d12NormalConstSmall, 0, -d12NormalConstBig)
				),
				new DieFace(
					"11",
					11,
					new Vector3f(-d12NormalConstBig, d12NormalConstSmall, 0)
				),
				new DieFace(
					"12",
					12,
					new Vector3f(-d12NormalConstBig, -d12NormalConstSmall, 0)
				),
			},
			/* D20. */
			{
				new DieFace(
					"1",
					1,
					new Vector3f(d20NormalConstSmall, d20NormalConstBig, 0)
				),
				new DieFace(
					"2",
					2,
					new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"3",
					3,
					new Vector3f(d20NormalConstBig, 0, d20NormalConstSmall)
				),
				new DieFace(
					"4",
					4,
					new Vector3f(-d20NormalConstBig, 0, d20NormalConstSmall)
				),
				new DieFace(
					"5",
					5,
					new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"6",
					6,
					new Vector3f(0, -d20NormalConstSmall, d20NormalConstBig)
				),
				new DieFace(
					"7",
					7,
					new Vector3f(sqrtOf1Div3, sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"8",
					8,
					new Vector3f(d20NormalConstSmall, -d20NormalConstBig, 0)
				),
				new DieFace(
					"9",
					9,
					new Vector3f(0, d20NormalConstSmall, d20NormalConstBig)
				),
				new DieFace(
					"10",
					10,
					new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, -sqrtOf1Div3)
				),
				new DieFace(
					"11",
					11,
					new Vector3f(-sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"12",
					12,
					new Vector3f(0, -d20NormalConstSmall, -d20NormalConstBig)
				),
				new DieFace(
					"13",
					13,
					new Vector3f(-d20NormalConstSmall, d20NormalConstBig, 0)
				),
				new DieFace(
					"14",
					14,
					new Vector3f(-sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"15",
					15,
					new Vector3f(0, d20NormalConstSmall, -d20NormalConstBig)
				),
				new DieFace(
					"16",
					16,
					new Vector3f(sqrtOf1Div3, -sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"17",
					17,
					new Vector3f(d20NormalConstBig, 0, -d20NormalConstSmall)
				),
				new DieFace(
					"18",
					18,
					new Vector3f(-d20NormalConstBig, 0, -d20NormalConstSmall)
				),
				new DieFace(
					"19",
					19,
					new Vector3f(sqrtOf1Div3, sqrtOf1Div3, sqrtOf1Div3)
				),
				new DieFace(
					"20",
					20,
					new Vector3f(-d20NormalConstSmall, -d20NormalConstBig, 0)
				),
			},
		};

		final DieFace[] dPercentFaces = faceArrays[d10Idx].clone();
		for (int i = 0; i < dPercentFaces.length; ++i) {
			final DieFace oldFace = dPercentFaces[i];
			final DieFace newFace = new DieFace(
				String.format("%s0", oldFace.displayValue()),
				oldFace.totalValue() * 10,
				oldFace.normal()
			);

			dPercentFaces[i] = newFace;
		}
		faceArrays[dPercentIdx] = dPercentFaces;

		this.dieTypes = IntStream.range(0, names.length)
			.mapToObj(
				i -> new DieType(
					names[i],
					prototypes[i],
					collisionShapes[i],
					faceArrays[i]
				)
			)
			.toArray(DieType[]::new);

		this.currentDieType = this.dieTypes[d6Idx];
	}

	private void createAndRollDie() {
		/* Create the die. */
		final Spatial die = this.currentDieType.prototype().clone();

		final RigidBodyControl dieBody =
			new RigidBodyControl(this.currentDieType.collisionShape());
		die.addControl(dieBody);

		this.rootNode.attachChild(die);
		this.physics.getPhysicsSpace().add(dieBody);
		this.dice.add(die);

		/* Roll the die,
		 * by applying a linear and angular impulse to it. */

		/* Randomize the die's initial position and rotation
		 * and the impulses applied to the die,
		 * to ensure randomness for the roll. */
		final float positionXzAbsMax = 1;
		final float positionY = 1.5f;
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

		final float linearImpulseXzAbsMax = 2;
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

	private static float vectorLengthApprox(final Vector3f v) {
		return Math.abs(v.getX())
			+ Math.abs(v.getY())
			+ Math.abs(v.getZ());
	}

	private DieFace readDieFace(final Spatial die) {
		/* If the "most upward" face of the die is exactly horizontal,
		 * then its outward unit normal is currently (0, 1, 0).
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
		 * is unlikely to be exactly horizontal,
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
		 * the product,
		 * of its original outward unit normal
		 * with the result of applying the inverse of the die's rotation
		 * to (0, 1, 0),
		 * is greatest. */
		final DieType dieType = this.currentDieType;

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

	private static record DieType(
		String name,
		Spatial prototype,
		CollisionShape collisionShape,
		DieFace[] faces
	) {}

	private static record DieFace(
		/* The value on the face. */
		String displayValue,
		/* The value used for computing the total roll result. */
		int totalValue,
		/* The face's outward unit normal. */
		Vector3f normal
	) {}

	private static enum CameraView {
		VERTICAL,
		DIAGONAL,
		HORIZONTAL;

		public Vector3f position() {
			return switch (this) {
				case VERTICAL -> new Vector3f(0f, 9f, 0f);
				case DIAGONAL -> new Vector3f(6f, 6f, 4f);
				case HORIZONTAL -> new Vector3f(5f, 5f, 0f);
			};
		}

		public Vector3f up() {
			return switch (this) {
				case VERTICAL -> Vector3f.UNIT_Z;
				case DIAGONAL -> Vector3f.UNIT_Y;
				case HORIZONTAL -> Vector3f.UNIT_X;
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
}

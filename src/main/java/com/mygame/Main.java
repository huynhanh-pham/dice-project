package com.mygame;

/**
 * @author thuph
 * @author Jonathan Gruber */
import com.jme3.app.SimpleApplication;

import com.jme3.bullet.BulletAppState;

import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.collision.shapes.HullCollisionShape;

import com.jme3.bullet.control.RigidBodyControl;

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
import com.jme3.scene.Mesh;

import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;

import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.SpotLightShadowRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import java.util.function.Supplier;

import java.util.stream.IntStream;

public class Main extends SimpleApplication {
	private static final float GROUND_SIZE = 20;
	private static final float DICE_TRAY_WIDTH = 6;
	private static final float DICE_TRAY_WALL_HEIGHT = 3.5f;
	private static final float DICE_TRAY_WALL_THICKNESS = 0.2f;

	private static final String HUD_COMMON_TEXT_COMMON_HEAD_TAIL_SEPARATOR =
		" ";

	private BulletAppState physics;
	private BitmapText hud;
	private CameraView cameraView;
	private DieType[] dieTypes;
	private DieType currentDieType;
	/* How many dice to roll. */
	private int numDice;
	private List<Geometry> dice;
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

		final int numDiceDefault = 1;
		this.numDice = numDiceDefault;
		this.dice = new ArrayList<>();
		/* For getting die-roll results in simpleUpdate. */
		this.settleTimer = 0;

		this.setupLights();
		this.setupDiceTray();
		this.setupHUD();
		this.setupInput();
	}

	@Override
	public void simpleUpdate(final float tpf) {
		if (this.dice.isEmpty()) {
			return;
		}

		float vSum = 0, wSum = 0;
		for (final Geometry die : this.dice) {
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

		this.settleTimer += tpf;
		final float settleTimerCutoff = 0.8f;
		if (settleTimer <= settleTimerCutoff) {
			return;
		}

		final StringBuilder sb = new StringBuilder();
		sb.append(System.lineSeparator())
			.append("Rolled: ");

		int total = 0;
		for (final Geometry die : this.dice) {
			final int result = this.readDieResult(die);
			sb.append(result).append(" ");
			total += result;
		}
		sb.append(System.lineSeparator())
			.append("Total = ")
			.append(total)
			.append(System.lineSeparator());

		this.hud.setText(this.getHudCommonText(sb.toString()));
	}

	private void setupLights() {
		final Vector3f sunlightDirection =
			new Vector3f(0.1f, -0.1f, 0.1f);
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
		final ColorRGBA wallDiffuseColor =
			new ColorRGBA(1,1,1,0.5f);
		final ColorRGBA wallSpecularColor =
			new ColorRGBA(0,0,0,0.5f);
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
		this.hud.setText(
			this.getHudCommonText(HUD_COMMON_TEXT_COMMON_HEAD_TAIL_SEPARATOR)
		);
		this.guiNode.attachChild(this.hud);
	}

	private Vector3f getHudPosition() {
		return new Vector3f(15, this.cam.getHeight() - 15, 0);
	}

	private String getHudCommonText(final String headTailSep) {
		return String.format(
			"Current Die: %s x%d%s(SPACE=roll; N=set count; C=camera)",
			this.currentDieType.name(), this.numDice, headTailSep
		);
	}

	private void setupInput() {
		/* Actions upon key triggers. */
		final String rollDiceActionName = "ROLL_DICE";
		final String cycleCameraViewActionName = "CYCLE_CAMERA_VIEW";
		final String setNumDiceActionName = "SET_NUM_DICE";
		final String confirmNumDiceActionName = "CONFIRM_NUM_DICE";
		final String numDiceDigitActionName = "NUM_DICE_DIGIT";

		/* Input mode and buffer for typing number of dice. */
		final InputModeAndBuffer inputModeAndBuffer =
			new InputModeAndBuffer();

		final ActionListener actionListener = new ActionListener() {
			@Override
			public void onAction(
				final String name,
				final boolean isPressed,
				final float tpf
			) {
				final Main main = Main.this;
				final InputModeAndBuffer imb = inputModeAndBuffer;

				if (isPressed) {
					return;
				}

				if (name.equals(rollDiceActionName)) {
					if (imb.inputMode) {
						return;
					}

					main.clearDice();
					for (int i = 0; i < main.numDice; ++i) {
						main.createAndRollDie(main.currentDieType);
					}
				} else if (name.equals(cycleCameraViewActionName)) {
					if (imb.inputMode) {
						return;
					}

					main.setCameraView(main.cameraView.next());
				} else if (name.equals(setNumDiceActionName)) {
					imb.inputMode = true;
					imb.inputBuffer.setLength(0);
					main.hud.setText(
						"Enter number of dice, then press ENTER:"
					);
				} else if (name.startsWith(numDiceDigitActionName)) {
					if (!imb.inputMode) {
						return;
					}

					/* DIGIT# -> #. */
					imb.inputBuffer.append(
						name.substring(numDiceDigitActionName.length())
					);
					main.hud.setText(
						String.format(
							"Enter number of dice: %s",
							imb.inputBuffer
						)
					);
				} else if (name.equals(confirmNumDiceActionName)) {
					if (!imb.inputMode) {
						return;
					}

					OptionalInt maybeValue = OptionalInt.empty();
					try {
						maybeValue = OptionalInt.of(
							Integer.parseInt(imb.inputBuffer.toString())
						);
					} catch (NumberFormatException e) {}

					final Supplier<String> getHudCommonText = () ->
						main.getHudCommonText(
							HUD_COMMON_TEXT_COMMON_HEAD_TAIL_SEPARATOR
						);

					maybeValue.ifPresentOrElse(
						value -> {
							if (value <= 0) {
								return;
							}

							final int numDiceMax = 100;

							main.numDice = Math.min(value, numDiceMax);

							System.out.println(main.numDice);

							if (value > numDiceMax) {
								main.hud.setText(
									String.format(
										"Max is %d dice! Using %d.%n%s",
										numDiceMax,
										numDiceMax,
										getHudCommonText.get()
									)
								);
							} else {
								main.hud.setText(getHudCommonText.get());
							}
						},
						() -> main.hud.setText(
							String.format(
								"Invalid number; keeping previous value.%n%s",
								getHudCommonText.get()
							)
						)
					);

					imb.inputMode = false;
					main.clearDice();
				}
			}
		};

		final String[] generalActions = {
			rollDiceActionName,
			cycleCameraViewActionName,
			setNumDiceActionName,
			confirmNumDiceActionName,
		};
		final int[] generalActionKeyCodes = {
			KeyInput.KEY_SPACE,
			KeyInput.KEY_C,
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
				String.format("%s%d", numDiceDigitActionName, i);

			this.inputManager.addMapping(digitAction, triggers);
			this.inputManager.addListener(actionListener, digitAction);
		}
	}

	private static class InputModeAndBuffer {
		public boolean inputMode = false;
		public final StringBuilder inputBuffer = new StringBuilder();
	}

	private void setCameraView(final CameraView cameraView) {
		this.cameraView = cameraView;
		this.cam.setLocation(this.cameraView.position());
		this.cam.lookAt(Vector3f.ZERO, this.cameraView.up());
	}

	private void clearDice() {
		for (final Geometry die : this.dice) {
			final RigidBodyControl dieBody =
				die.getControl(RigidBodyControl.class);

			this.physics.getPhysicsSpace().remove(dieBody);
			this.rootNode.detachChild(die);
		}
		this.dice.clear();
	}

	private void initDieTypes() {
		final String[] names = {
			"D6",
		};

		final DieFace[][] faceArrays = {
			/* D6. */
			{
				new DieFace(Vector3f.UNIT_Y, 1),
				new DieFace(Vector3f.UNIT_Y.negate(), 6),
				new DieFace(Vector3f.UNIT_Z, 2),
				new DieFace(Vector3f.UNIT_Z.negate(), 5),
				new DieFace(Vector3f.UNIT_X, 3),
				new DieFace(Vector3f.UNIT_X.negate(), 4),
			},
		};

		final float dieD6HalfExtent = 0.25f;

		final Mesh[] meshes = {
			/* D6. */
			new Box(dieD6HalfExtent, dieD6HalfExtent, dieD6HalfExtent),
		};

		final CollisionShape[] collisionShapes = Arrays.stream(meshes)
			.map(HullCollisionShape::new)
			.toArray(CollisionShape[]::new);

		this.dieTypes = IntStream.range(0, names.length)
			.mapToObj(
				i -> new DieType(
					names[i],
					faceArrays[i],
					meshes[i],
					collisionShapes[i]
				)
			)
			.toArray(DieType[]::new);

		final String defaultDieTypeName = "D6";
		for (final DieType type : this.dieTypes) {
			if (type.name().equals(defaultDieTypeName)) {
				this.currentDieType = type;
				break;
			}
		}

		assert this.currentDieType != null;
	}

	private void createAndRollDie(DieType type) {
		/* Create the die. */
		final Material mat = new Material(
			this.assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		mat.setBoolean("UseMaterialColors", true);
		mat.setColor("Diffuse", ColorRGBA.Yellow);

		final Geometry die = new Geometry(type.name(), type.mesh(), mat);

		final RigidBodyControl dieBody =
			new RigidBodyControl(type.collisionShape());
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

	private int readDieResult(final Geometry die) {
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

		int bestValue = 0;
		float bestDot = Float.NEGATIVE_INFINITY;
		for (final DieFace face : dieType.faces()) {
			final float dot = upFaceOriginalNormal.dot(face.normal());
			if (dot > bestDot) {
				bestDot = dot;
				bestValue = face.value();
			}
		}

		return bestValue;
	}

	private static record DieType(
		String name,
		DieFace[] faces,
		Mesh mesh,
		CollisionShape collisionShape
	) {}

	private static record DieFace(
		/* The face's outward unit normal. */
		Vector3f normal,
		/* The value on the face. */
		int value
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

package com.mygame;

/**
 * @author thuph
 * @author Jonathan Gruber
 */
import com.jme3.app.SimpleApplication;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.SpotLightShadowRenderer;

import java.util.*;

public class Main extends SimpleApplication {
	private static final float DIE_HALF = 0.25f;

	private static final float GROUND_SIZE = 20;
	private static final float DICE_TRAY_WIDTH = 6;
	private static final float DICE_TRAY_WALL_HEIGHT = 3.5f;
	private static final float DICE_TRAY_WALL_THICKNESS = 0.2f;

	private static final Vector3f SUNLIGHT_DIRECTION =
		new Vector3f(0.1f, -0.1f, 0.1f);
	private static final ColorRGBA SUNLIGHT_COLOR = ColorRGBA.White;

	private static final ColorRGBA AMBIENT_LIGHT_COLOR = ColorRGBA.White;

	private static final int DLSR_SHADOW_MAP_SIZE = 1024;
	private static final int DLSR_SPLIT_COUNT = 3;

	private static final ColorRGBA GROUND_DIFFUSE_COLOR =
		new ColorRGBA(0.90f, 0.92f, 0.95f, 1);
	private static final ColorRGBA GROUND_SPECULAR_COLOR = ColorRGBA.White;
	private static final float GROUND_SHININESS = 8f;
	private static RenderQueue.ShadowMode GROUND_SHADOW_MODE =
		RenderQueue.ShadowMode.Receive;

	private static final ColorRGBA WALL_DIFFUSE_COLOR =
		new ColorRGBA(1,1,1,0.5f);
	private static final ColorRGBA WALL_SPECULAR_COLOR =
		new ColorRGBA(0,0,0,0.5f);
	private static final float WALL_SHININESS = 16f;

	private static final float HUD_TEXT_SIZE = 24;
	private static final ColorRGBA HUD_TEXT_COLOR = ColorRGBA.Brown;

	// Face normal → value map
	private static final Map<String, DieFace[]> faceMaps = new HashMap<>();

	private BulletAppState physics;
	private List<Node> diceList = new ArrayList<>();
	private List<RigidBodyControl> diceBodies = new ArrayList<>();
	private BitmapText hud;
	private float settleTimer = 0f;

	private CamMode camMode;

	// Die state
	private String currentDieType = "D6"; // start with D6
	private int numDice = 1;			  // number of dice to roll

	// input mode for typing number
	private boolean inputMode = false;
	private StringBuilder inputBuffer = new StringBuilder();

	public static void main(String[] args) {
		Main app = new Main();
		app.setShowSettings(false);
		app.start();
	}

	@Override
	public void simpleInitApp() {
		this.physics = new BulletAppState();
		this.stateManager.attach(this.physics);

		this.camMode = CamMode.VERTICAL;
		this.setCameraView();
		this.flyCam.setEnabled(false);

		this.setupLights();
		this.setupDiceTray();
		this.setupHUD();
		this.setupInput();
		this.initFaceMaps();

		// start with one die ready
		this.rollDice(currentDieType);
	}

	private void setupLights() {
		DirectionalLight sunlight = new DirectionalLight();
		sunlight.setDirection(SUNLIGHT_DIRECTION);
		sunlight.setColor(SUNLIGHT_COLOR);
		this.rootNode.addLight(sunlight);

		AmbientLight amb = new AmbientLight();
		amb.setColor(AMBIENT_LIGHT_COLOR);
		this.rootNode.addLight(amb);

		DirectionalLightShadowRenderer dlsr =
			new DirectionalLightShadowRenderer(
				assetManager,
				DLSR_SHADOW_MAP_SIZE,
				DLSR_SPLIT_COUNT
			);
		dlsr.setLight(sunlight);
		this.viewPort.addProcessor(dlsr);
	}

	private void setupDiceTray() {
		this.setupGround();
		this.setupWalls();
	}

	private void setupGround() {
		Geometry ground =
			new Geometry("ground", new Quad(GROUND_SIZE, GROUND_SIZE));
		ground.rotate(-FastMath.HALF_PI, 0, 0);
		ground.setLocalTranslation(-GROUND_SIZE / 2, 0, GROUND_SIZE / 2);

		Material m = new Material(
			assetManager, "Common/MatDefs/Light/Lighting.j3md"
		);
		m.setBoolean("UseMaterialColors", true);
		m.setColor("Diffuse", GROUND_DIFFUSE_COLOR);
		m.setColor("Specular", GROUND_SPECULAR_COLOR);
		m.setFloat("Shininess", GROUND_SHININESS);
		ground.setMaterial(m);
		ground.setShadowMode(GROUND_SHADOW_MODE);

		this.rootNode.attachChild(ground);

		RigidBodyControl groundBody = new RigidBodyControl(0f);
		ground.addControl(groundBody);
		this.physics.getPhysicsSpace().add(groundBody);
	}

	private void setupWalls() {
		Material wallMat = new Material(
			assetManager,
			"Common/MatDefs/Light/Lighting.j3md"
		);
		wallMat.setBoolean("UseMaterialColors", true);
		wallMat.setColor("Diffuse", WALL_DIFFUSE_COLOR);
		wallMat.setColor("Specular", WALL_SPECULAR_COLOR);
   
		wallMat.setTransparent(true);
		wallMat.setFloat("Shininess", WALL_SHININESS);

		Vector3f[] wallPositions = {
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
		float[][] wallDimensions = {
			/* Near wall. */
			{ DICE_TRAY_WIDTH, DICE_TRAY_WALL_HEIGHT, DICE_TRAY_WALL_THICKNESS },
			/* Far wall. */
			{ DICE_TRAY_WIDTH, DICE_TRAY_WALL_HEIGHT, DICE_TRAY_WALL_THICKNESS },
			/* Right wall. */
			{ DICE_TRAY_WALL_THICKNESS, DICE_TRAY_WALL_HEIGHT, DICE_TRAY_WIDTH },
			/* Left wall. */
			{ DICE_TRAY_WALL_THICKNESS, DICE_TRAY_WALL_HEIGHT, DICE_TRAY_WIDTH },
		};

		for (int i = 0; i < wallPositions.length; ++i) {
			Vector3f position = wallPositions[i];
			float[] wallDim = wallDimensions[i];
			float xSize = wallDim[0];
			float ySize = wallDim[1];
			float zSize = wallDim[2];

			this.addWall(position, xSize, ySize, zSize, wallMat);
		}
	}

	private void addWall(Vector3f pos, float xSize, float ySize, float zSize, Material mat) {
		Geometry wall = new Geometry("wall", new Box(xSize / 2f, ySize / 2f, zSize / 2f));
		wall.setMaterial(mat);
		wall.setLocalTranslation(pos);
		wall.setShadowMode(RenderQueue.ShadowMode.Receive);
		rootNode.attachChild(wall);

		RigidBodyControl wallBody = new RigidBodyControl(0f);
		wall.addControl(wallBody);
		physics.getPhysicsSpace().add(wallBody);
	}

	private void setupHUD() {
		this.guiNode.detachAllChildren();
		guiFont = this.assetManager.loadFont("Interface/Fonts/Default.fnt");
		hud = new BitmapText(guiFont);
		this.setDisplayStatView(false); 
		this.setDisplayFps(false);
		hud.setSize(HUD_TEXT_SIZE);
		hud.setLocalTranslation(this.getHudPosition());
		hud.setColor(HUD_TEXT_COLOR);
		this.guiNode.attachChild(hud);
		hud.setText(
			String.format(
				"Current Die: %s x %d (SPACE=roll; N=set count; C=cam)",
				this.currentDieType, this.numDice
			)
		);
	}

	private Vector3f getHudPosition() {
		return new Vector3f(15, cam.getHeight() - 15, 0);
	}

	private void setupInput() {
		inputManager.addMapping("ROLL", new KeyTrigger(KeyInput.KEY_SPACE));
		inputManager.addMapping("TOGGLE_CAM", new KeyTrigger(KeyInput.KEY_C));
		inputManager.addMapping("SET_NUM", new KeyTrigger(KeyInput.KEY_N));
		inputManager.addMapping("CONFIRM_NUM", new KeyTrigger(KeyInput.KEY_RETURN));

		// FIXME: use loop, allow number pad keys too
		// digits 0–9
		inputManager.addMapping("DIGIT0", new KeyTrigger(KeyInput.KEY_0));
		inputManager.addMapping("DIGIT1", new KeyTrigger(KeyInput.KEY_1));
		inputManager.addMapping("DIGIT2", new KeyTrigger(KeyInput.KEY_2));
		inputManager.addMapping("DIGIT3", new KeyTrigger(KeyInput.KEY_3));
		inputManager.addMapping("DIGIT4", new KeyTrigger(KeyInput.KEY_4));
		inputManager.addMapping("DIGIT5", new KeyTrigger(KeyInput.KEY_5));
		inputManager.addMapping("DIGIT6", new KeyTrigger(KeyInput.KEY_6));
		inputManager.addMapping("DIGIT7", new KeyTrigger(KeyInput.KEY_7));
		inputManager.addMapping("DIGIT8", new KeyTrigger(KeyInput.KEY_8));
		inputManager.addMapping("DIGIT9", new KeyTrigger(KeyInput.KEY_9));

		inputManager.addListener(actionListener,
			"ROLL","TOGGLE_CAM","SET_NUM","CONFIRM_NUM",
			"DIGIT0","DIGIT1","DIGIT2","DIGIT3","DIGIT4","DIGIT5","DIGIT6","DIGIT7","DIGIT8","DIGIT9");
	}

	private final ActionListener actionListener = new ActionListener() {
		@Override
		public void onAction(String name, boolean isPressed, float tpf) {
			if (!isPressed) {
				if ("ROLL".equals(name) && !inputMode) {
					clearDice();
					for (int i = 0; i < numDice; i++) {
						rollDice(currentDieType);
					}
				} else if ("TOGGLE_CAM".equals(name) && !inputMode) {
					Main.this.camMode = Main.this.camMode.next();
					Main.this.setCameraView();
				}  else if ("SET_NUM".equals(name)) {
					inputMode = true;
					inputBuffer.setLength(0);
					hud.setText("Enter number of dice, then press ENTER:");
				} else if (name.startsWith("DIGIT") && inputMode) {
					inputBuffer.append(name.substring(5)); // "DIGIT3" -> "3"
					hud.setText("Enter number of dice: " + inputBuffer.toString());
				} else if ("CONFIRM_NUM".equals(name) && inputMode) {
					try {
						int value = Integer.parseInt(inputBuffer.toString());
						if (value > 0) {
							if (value > 100) {
								numDice = 100;
								hud.setText("Max is 100 dice! Using 100.\n" +
											"Current Die: " + currentDieType + " x" + numDice +
											" (SPACE=roll,  N=set count, C=cam)");
							} else {
								numDice = value;
								hud.setText("Current Die: " + currentDieType + " x" + numDice +
											" (SPACE=roll,  N=set count, C=cam)");
							}
						}
					} catch (NumberFormatException e) {
						hud.setText("Invalid number, keeping previous value.\n" +
									"Current Die: " + currentDieType + " x" + numDice +
									" (SPACE=roll, N=set count, C=cam)");
					}
					inputMode = false;
					clearDice();
				}
			}
		}
	};

	private void setCameraView() {
		this.cam.setLocation(this.camMode.position());
		this.cam.lookAt(Vector3f.ZERO, this.camMode.up());
	}

	private void rollDice(String type) {
		Node die = createD6();
				
		diceList.add(die);
		RigidBodyControl body = die.getControl(RigidBodyControl.class);
		diceBodies.add(body);
		//Random physic to ensure dice randomness when roll
		body.setPhysicsLocation(new Vector3f(
				(FastMath.nextRandomFloat() - 0.5f) * 2f, 1.5f,
				(FastMath.nextRandomFloat() - 0.5f) * 2f));
		body.setPhysicsRotation(new Quaternion().fromAngles(
				FastMath.nextRandomFloat() * FastMath.TWO_PI,
				FastMath.nextRandomFloat() * FastMath.TWO_PI,
				FastMath.nextRandomFloat() * FastMath.TWO_PI));
		body.setLinearVelocity(Vector3f.ZERO);
		body.setAngularVelocity(Vector3f.ZERO);

		body.applyImpulse(new Vector3f(
				FastMath.nextRandomFloat() * 4f - 2f,
				6f,
				FastMath.nextRandomFloat() * 4f - 2f), Vector3f.ZERO);
		body.applyTorque(new Vector3f(
				FastMath.nextRandomFloat() * 8f - 4f,
				FastMath.nextRandomFloat() * 8f - 4f,
				FastMath.nextRandomFloat() * 8f - 4f));
	}

	private void addFaceNumber(Node die, String textVal, Vector3f pos, Quaternion rot) {
		BitmapText text = new BitmapText(guiFont, false);
		text.setText(textVal);
		text.setSize(0.3f);
		text.setColor(ColorRGBA.Black);

		Node textNode = new Node("Face" + textVal);
		textNode.attachChild(text);
		text.setLocalTranslation(-text.getLineWidth() / 2, text.getLineHeight() / 2, 0);

		textNode.setLocalTranslation(pos);
		textNode.setLocalRotation(rot);

		die.attachChild(textNode);
	}
	//Cube with numbered sides, may change in the future to make size uniform with other dice type
	private Node createD6() {
		Geometry cube = new Geometry("D6", new Box(DIE_HALF, DIE_HALF, DIE_HALF));
		Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
		mat.setBoolean("UseMaterialColors", true);
		mat.setColor("Diffuse", ColorRGBA.Yellow);
		cube.setMaterial(mat);
		cube.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);

		Node die = new Node("D6");
		die.attachChild(cube);

		float offset = DIE_HALF + 0.03f;
		addFaceNumber(die, "1", new Vector3f(0, offset, 0), new Quaternion().fromAngles(-FastMath.HALF_PI, 0, 0));
		addFaceNumber(die, "6", new Vector3f(0, -offset, 0), new Quaternion().fromAngles(FastMath.HALF_PI, 0, 0));
		addFaceNumber(die, "2", new Vector3f(0, 0, offset), Quaternion.IDENTITY);
		addFaceNumber(die, "5", new Vector3f(0, 0, -offset), new Quaternion().fromAngles(0, FastMath.PI, 0));
		addFaceNumber(die, "4", new Vector3f(-offset, 0, 0), new Quaternion().fromAngles(0, -FastMath.HALF_PI, 0));
		addFaceNumber(die, "3", new Vector3f(offset, 0, 0), new Quaternion().fromAngles(0, FastMath.HALF_PI, 0));

		RigidBodyControl body = new RigidBodyControl(new BoxCollisionShape(new Vector3f(DIE_HALF, DIE_HALF, DIE_HALF)), 1f);
		die.addControl(body);
		physics.getPhysicsSpace().add(body);

		die.setUserData("type", "D6");
		die.setUserData("sides", 6);

		rootNode.attachChild(die);
		return die;
	}

	@Override
	public void simpleUpdate(float tpf) {
		float vSum = 0, wSum = 0;
		for (RigidBodyControl body : diceBodies) {
			vSum += body.getLinearVelocity().length();
			wSum += body.getAngularVelocity().length();
		}
		
		//Read, calculate dice sum and display
		if (vSum < 0.1f && wSum < 0.1f) {
			settleTimer += tpf;
			if (settleTimer > 0.8f && !diceBodies.isEmpty()) {
				StringBuilder sb = new StringBuilder("Rolled: ");
				int total = 0;
				for (RigidBodyControl body : diceBodies) {
					int result = readDieResult(body);
					sb.append(result).append(" ");
					total += result;
				}
				sb.append("\nTotal = ").append(total);
				hud.setText("Current Die: " + currentDieType + " x" + numDice +
							"\n" + sb.toString() +
							"\n(SPACE=roll, N=set count, C=cam)");
			}
		} else {
			settleTimer = 0f;
		}
	}

	private int readDieResult(RigidBodyControl body) {
		Node die = (Node) body.getSpatial();
		String type = die.getUserData("type");
		DieFace[] faces = faceMaps.get(type);

		Quaternion rot = body.getPhysicsRotation();
		Vector3f up = Vector3f.UNIT_Y;

		float bestDot = -Float.MAX_VALUE;
		int bestVal = 1;
		for (DieFace f : faces) {
			Vector3f nWorld = rot.mult(f.normal);
			float d = nWorld.dot(up);
			if (d > bestDot) {
				bestDot = d;
				bestVal = f.value;
			}
		}
		return bestVal;
	}

	private void clearDice() {
		for (Node die : diceList) {
			physics.getPhysicsSpace().remove(die.getControl(RigidBodyControl.class));
			rootNode.detachChild(die);
		}
		diceList.clear();
		diceBodies.clear();
	}

	private void initFaceMaps() {
		// D6 normals
		faceMaps.put("D6", new DieFace[] {
			new DieFace(Vector3f.UNIT_Y, 1),
			new DieFace(Vector3f.UNIT_Y.negate(), 6),
			new DieFace(Vector3f.UNIT_Z, 2),
			new DieFace(Vector3f.UNIT_Z.negate(), 5),
			new DieFace(Vector3f.UNIT_X, 3),
			new DieFace(Vector3f.UNIT_X.negate(), 4)
		});
	}

	private static class DieFace {
		final Vector3f normal;
		final int value;
		DieFace(Vector3f n, int v) {
			this.normal = n;
			this.value = v;
		}
	}

	private enum CamMode {
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

		public CamMode next() {
			return switch (this) {
				case VERTICAL -> DIAGONAL;
				case DIAGONAL -> HORIZONTAL;
				case HORIZONTAL -> VERTICAL;
			};
		}
	}
}


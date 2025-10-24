/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mygame;

/**
 *
 * @author thuph
 */
import com.jme3.app.SimpleApplication;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.HullCollisionShape;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.font.BitmapText;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.shadow.SpotLightShadowRenderer;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferUtils;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;

import java.util.*;

public class DiceRollApp extends SimpleApplication {

    private BulletAppState physics;
    private List<Node> diceList = new ArrayList<>();
    private List<RigidBodyControl> diceBodies = new ArrayList<>();
    private BitmapText hud;
    private float settleTimer = 0f;

    private static final float DIE_HALF = 0.25f;

    // camera modes
    private int camMode = 0;

    // Die state
    private String currentDieType = "D6"; // start with D6
    private int numDice = 1;              // number of dice to roll

    // input mode for typing number
    private boolean inputMode = false;
    private StringBuilder inputBuffer = new StringBuilder();

   

    // Face normal → value map
    private static final Map<String, DieFace[]> faceMaps = new HashMap<>();

    public static void main(String[] args) {
        //AppSettings settings = new AppSettings(true);
       // settings.setResolution(1640,1480);
        //settings.setFullscreen(true);
        DiceRollApp app = new DiceRollApp();
       // app.setSettings(settings);
        app.setShowSettings(false);
        
        app.start();
    }

   
    @Override
    public void simpleInitApp() {
        physics = new BulletAppState();
        stateManager.attach(physics);

        flyCam.setMoveSpeed(10f);
        setCameraView(0);
        flyCam.setEnabled(false);

        setupLights();
        setupGround();
        setupHUD();
        setupInput();
        initFaceMaps();

        // start with one die ready
        rollDice(currentDieType);
    }

    private void setupLights() {
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(0.1f, -0.1f, 0.1f));
        sun.setColor(ColorRGBA.White.mult(1.0f));
        rootNode.addLight(sun);
        
       
        AmbientLight amb = new AmbientLight();
        amb.setColor(ColorRGBA.White.mult(1.0f));
        rootNode.addLight(amb);

       DirectionalLightShadowRenderer dlsr = new DirectionalLightShadowRenderer(assetManager, 1024, 3);
        dlsr.setLight(sun);
        viewPort.addProcessor(dlsr);
        
        
    }

    private void setupGround() {
        Geometry ground = new Geometry("ground", new Quad(20, 20));
        ground.rotate(-FastMath.HALF_PI, 0, 0);
        ground.setLocalTranslation(-10, 0, 10);

        Material m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Diffuse", new ColorRGBA(0.90f, 0.92f, 0.95f, 1));
        m.setColor("Specular", ColorRGBA.White);
        m.setFloat("Shininess", 8f);
        ground.setMaterial(m);
        ground.setShadowMode(RenderQueue.ShadowMode.Receive);
        rootNode.attachChild(ground);

        RigidBodyControl groundBody = new RigidBodyControl(0f);
        ground.addControl(groundBody);
        physics.getPhysicsSpace().add(groundBody);

        setupDiceTray();
    }

    private void setupDiceTray() {
        float traySize = 6f;
        float wallHeight = 3.5f;
        float wallThickness = 0.2f;

        Material wallMat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
       // wallMat.setBoolean("UseAlpha",true);
        wallMat.setBoolean("UseMaterialColors", true);
        wallMat.setColor("Diffuse", new ColorRGBA(1,1,1,0.5f));
        wallMat.setColor("Specular", new ColorRGBA(0,0,0,0.5f));
   
        wallMat.setTransparent(true);
        wallMat.setFloat("Shininess", 16f);

        addWall(new Vector3f(0, wallHeight / 2f, traySize / 2f),
                traySize, wallHeight, wallThickness, wallMat);
        addWall(new Vector3f(0, wallHeight / 2f, -traySize / 2f),
                traySize, wallHeight, wallThickness, wallMat);
        addWall(new Vector3f(traySize / 2f, wallHeight / 2f, 0),
                wallThickness, wallHeight, traySize, wallMat);
        addWall(new Vector3f(-traySize / 2f, wallHeight / 2f, 0),
                wallThickness, wallHeight, traySize, wallMat);
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
        guiNode.detachAllChildren();
        guiFont = assetManager.loadFont("Interface/Fonts/Default.fnt");
        hud = new BitmapText(guiFont, false);
        setDisplayStatView(false); 
        setDisplayFps(false);
        hud.setSize(24);
        hud.setLocalTranslation(15, cam.getHeight() - 15, 0);
        hud.setColor(ColorRGBA.Brown);
        guiNode.attachChild(hud);
        hud.setText("Current Die: " + currentDieType + " x" + numDice +
                    " (SPACE=roll,  N=set count, C=cam)");
    }

    private void setupInput() {
        inputManager.addMapping("ROLL", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("TOGGLE_CAM", new KeyTrigger(KeyInput.KEY_C));
        inputManager.addMapping("SET_NUM", new KeyTrigger(KeyInput.KEY_N));
        inputManager.addMapping("CONFIRM_NUM", new KeyTrigger(KeyInput.KEY_RETURN));

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
                    camMode = (camMode + 1) % 3;
                    setCameraView(camMode);
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

    private void setCameraView(int mode) {
        if (mode == 0) {
            cam.setLocation(new Vector3f(0f, 9f, 0f));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        } else if (mode == 1) {
            cam.setLocation(new Vector3f(6f, 6f, 4f));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        } else if (mode == 2) {
            cam.setLocation(new Vector3f(5f, 5f, 0f));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        }
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
        faceMaps.put("D6", new DieFace[]{
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
}


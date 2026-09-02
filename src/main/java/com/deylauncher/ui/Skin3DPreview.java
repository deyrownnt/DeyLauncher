package com.deylauncher.ui;

import com.deylauncher.identity.SkinModel;
import javafx.animation.AnimationTimer;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

/**
 * A real (not flat) Minecraft-style player model, built as a handful of textured boxes
 * (head/body/arms/legs, cape optional) whose UV coordinates follow Minecraft's own skin
 * layout -- the same box-unwrap every third-party skin viewer uses, just implemented
 * directly with JavaFX TriangleMesh so this doesn't pull in a 3D asset library for one
 * feature. Auto-rotates slowly; the user can also drag to spin it manually.
 *
 * Deliberately base layer only (no separate hat/jacket/sleeve/pants overlay boxes) to keep
 * this readable -- the base skin is still the real uploaded texture, correctly mapped per
 * body part, not a placeholder.
 */
public class Skin3DPreview extends StackPane {

    private final Group modelGroup = new Group();
    // Start upright. Only yaw is animated; a fixed pitch made the player lean like "/".
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    private double anchorX, anchorAngle;
    private boolean autoRotate = true;
    private AnimationTimer timer;

    public Skin3DPreview(double width, double height) {
        setPrefSize(width, height);
        setMinSize(width, height);

        modelGroup.getTransforms().add(rotateY);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(2000);
        camera.setTranslateZ(-90);
        camera.setFieldOfView(28);

        Group sceneRoot = new Group(modelGroup);
        SubScene subScene = new SubScene(sceneRoot, width, height, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);
        subScene.setCamera(camera);
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());

        AmbientLight ambient = new AmbientLight(Color.color(0.55, 0.55, 0.6));
        PointLight key = new PointLight(Color.WHITE);
        key.setTranslateX(-60);
        key.setTranslateY(-120);
        key.setTranslateZ(-150);
        sceneRoot.getChildren().addAll(ambient, key);

        getChildren().add(subScene);
        getStyleClass().add("skin-3d-preview");

        setOnMousePressed(this::onPressed);
        setOnMouseDragged(this::onDragged);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (autoRotate) {
                    rotateY.setAngle(rotateY.getAngle() + 0.25);
                }
            }
        };
        timer.start();
    }

    private void onPressed(MouseEvent e) {
        autoRotate = false;
        anchorX = e.getSceneX();
        anchorAngle = rotateY.getAngle();
    }

    private void onDragged(MouseEvent e) {
        rotateY.setAngle(anchorAngle + (e.getSceneX() - anchorX) * 0.6);
    }

    public void stop() {
        timer.stop();
    }

    /** Rebuilds the whole model from a fresh skin image, model variant, and optional cape image. */
    public void update(Image skin, SkinModel model, Image cape) {
        modelGroup.getChildren().setAll(buildBody(skin, model, cape));
    }

    private Group buildBody(Image skin, SkinModel model, Image cape) {
        Group g = new Group();
        skin = pixelated(skin);
        double tw = skin.getWidth();
        double th = skin.getHeight();
        boolean slim = model == SkinModel.SLIM;
        double armW = slim ? 3 : 4;

        // The body occupies Y=-6..+6, so the 8px head must occupy Y=-14..-6.
        // Keeping its centre at -10 makes the neck meet the body exactly; the old
        // -14 value left a visible four-pixel gap between head and torso.
        MeshView head = boxPart(skin, tw, th, 8, 8, 8, 0, 0, false);
        head.setTranslateY(-10);
        g.getChildren().add(head);

        // Body: 8x12x4, origin (16,16)
        MeshView body = boxPart(skin, tw, th, 8, 12, 4, 16, 16, false);
        body.setTranslateY(0);
        g.getChildren().add(body);

        // Right arm (viewer's left), origin (40,16)
        MeshView rightArm = boxPart(skin, tw, th, armW, 12, 4, 40, 16, false);
        rightArm.setTranslateX(-(4 + armW / 2.0));
        rightArm.setTranslateY(0);
        g.getChildren().add(rightArm);

        // Left arm, origin (32,48) on a 64-tall texture; falls back to mirrored right-arm
        // region on legacy 64x32 skins, which have no separate left-arm/leg data.
        boolean hasSecondLayer = th >= 64;
        MeshView leftArm = hasSecondLayer
                ? boxPart(skin, tw, th, armW, 12, 4, 32, 48, false)
                : boxPart(skin, tw, th, armW, 12, 4, 40, 16, true);
        leftArm.setTranslateX(4 + armW / 2.0);
        g.getChildren().add(leftArm);

        // Right leg, origin (0,16)
        MeshView rightLeg = boxPart(skin, tw, th, 4, 12, 4, 0, 16, false);
        rightLeg.setTranslateX(-2);
        rightLeg.setTranslateY(12);
        g.getChildren().add(rightLeg);

        // Left leg, origin (16,48) on 64-tall; mirrored fallback on legacy skins
        MeshView leftLeg = hasSecondLayer
                ? boxPart(skin, tw, th, 4, 12, 4, 16, 48, false)
                : boxPart(skin, tw, th, 4, 12, 4, 0, 16, true);
        leftLeg.setTranslateX(2);
        leftLeg.setTranslateY(12);
        g.getChildren().add(leftLeg);

        if (cape != null) {
            cape = pixelated(cape);
            MeshView capeMesh = boxPart(cape, cape.getWidth(), cape.getHeight(), 10, 16, 1, 0, 0, false);
            // The skin's front is +Z in this mesh; -Z is the player's back. Keep the cape
            // vertical and farther than the torso so it can never cross the face.
            // Attach at the shoulders: its top is just below the head rather than halfway
            // across the face (a 16px-high cape centred at +3 spans Y=-5..+11).
            capeMesh.setTranslateY(3);
            capeMesh.setTranslateZ(-3.1);
            capeMesh.setRotationAxis(Rotate.Y_AXIS);
            capeMesh.setRotate(180);
            g.getChildren().add(capeMesh);
        }
        return g;
    }

    /** JavaFX's PhongMaterial uses filtered texture sampling. Expand every source texel into
     * a solid block before mapping it, which preserves the original Minecraft pixel-art look. */
    private Image pixelated(Image source) {
        PixelReader reader = source.getPixelReader();
        if (reader == null || source.getWidth() <= 0 || source.getHeight() <= 0) return source;
        final int scale = 8;
        int width = (int) source.getWidth();
        int height = (int) source.getHeight();
        WritableImage out = new WritableImage(width * scale, height * scale);
        var writer = out.getPixelWriter();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) {
            var color = reader.getColor(x, y);
            for (int dx = 0; dx < scale; dx++) for (int dy = 0; dy < scale; dy++)
                writer.setColor(x * scale + dx, y * scale + dy, color);
        }
        return out;
    }

    /**
     * Builds one Minecraft-style textured box using the standard skin UV unwrap:
     * for a box of size (w,h,d) whose texture region starts at (ox,oy):
     *   top    = (ox+d,        oy,        w, d)
     *   bottom = (ox+d+w,      oy,        w, d)
     *   right  = (ox,          oy+d,      d, h)
     *   front  = (ox+d,        oy+d,      w, h)
     *   left   = (ox+d+w,      oy+d,      d, h)
     *   back   = (ox+d+w+d,    oy+d,      w, h)
     * mirrorX flips left/right (used to fake a left limb from right-limb texture data on
     * legacy 64x32 skins, which never recorded the left side separately).
     */
    private MeshView boxPart(Image tex, double tw, double th, double w, double h, double d,
                              double ox, double oy, boolean mirrorX) {
        TriangleMesh mesh = new TriangleMesh();
        float x = (float) (w / 2), y = (float) (h / 2), z = (float) (d / 2);

        // 24 verts (4 per face, not shared) so each face can carry its own UV rect --
        // a shared-vertex cube can't do that in JavaFX's mesh format.
        float[][] facePts = {
                { -x, -y, z,  x, -y, z,  x, -y, -z,  -x, -y, -z }, // top (-y up in JavaFX y-down... treat -y as up)
                { -x, y, -z,  x, y, -z,  x, y, z,  -x, y, z },     // bottom
                { -x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z },    // right (-x)
                { -x, -y, z,  x, -y, z,  x, y, z,  -x, y, z },     // front (+z)
                { x, -y, z,  x, -y, -z,  x, y, -z,  x, y, z },     // left (+x)
                { x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z }     // back (-z)
        };
        double[][] rects = {
                { ox + d, oy, w, d },
                { ox + d + w, oy, w, d },
                { mirrorX ? ox + d : ox, oy + d, d, h },
                { ox + d, oy + d, w, h },
                { mirrorX ? ox : ox + d + w, oy + d, d, h },
                { ox + d + w + d, oy + d, w, h }
        };
        int pIdx = 0, tIdx = 0;
        for (int face = 0; face < 6; face++) {
            float[] p = facePts[face];
            mesh.getPoints().addAll(p);
            double[] rc = rects[face];
            // Textures may have been expanded by pixelated(); retain the original 64x64
            // Minecraft-coordinate UV layout while using its enlarged texel blocks.
            double scale = tw / 64.0;
            double sx = rc[0] * scale, sy = rc[1] * scale, sw = rc[2] * scale, sh = rc[3] * scale;
            float u0 = (float) (sx / tw), v0 = (float) (sy / th);
            float u1 = (float) ((sx + sw) / tw), v1 = (float) ((sy + sh) / th);
            mesh.getTexCoords().addAll(u0, v0, u1, v0, u1, v1, u0, v1);
            int base = pIdx;
            int tbase = tIdx;
            mesh.getFaces().addAll(
                    base, tbase, base + 1, tbase + 1, base + 2, tbase + 2,
                    base, tbase, base + 2, tbase + 2, base + 3, tbase + 3
            );
            pIdx += 4;
            tIdx += 4;
        }

        MeshView view = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseMap(tex);
        view.setMaterial(material);
        view.setCullFace(javafx.scene.shape.CullFace.NONE);
        return view;
    }
}

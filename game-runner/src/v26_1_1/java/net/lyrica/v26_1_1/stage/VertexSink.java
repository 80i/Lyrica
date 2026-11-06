package net.lyrica.v26_1_1.stage;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Letter geometry in this game version: the text format with a white texture, a triangle being a folded quad. */
final class VertexSink implements GeometrySink {

  private final StageBuffers.Pass pass;
  private final RenderType type;
  private VertexConsumer out;

  VertexSink(StageBuffers.Pass pass, RenderType type) {
    this.pass = pass;
    this.type = type;
  }

  /** Buffers end with every draw, so this comes once a frame before anything is written. */
  void open() {
    this.out = this.pass.getBuffer(this.type);
  }

  @Override
  public void quad(float x0, float y0, float z0, int color0, float x1, float y1, float z1, int color1, float x2,
      float y2, float z2, int color2, float x3, float y3, float z3, int color3, int light) {
    VertexConsumer to = this.out;
    to.addVertex(x0, y0, z0).setColor(color0).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x1, y1, z1).setColor(color1).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x2, y2, z2).setColor(color2).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x3, y3, z3).setColor(color3).setUv(0.0F, 0.0F).setLight(light);
  }

  @Override
  public void triangle(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
      int color, int light) {
    VertexConsumer to = this.out;
    to.addVertex(x0, y0, z0).setColor(color).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x1, y1, z1).setColor(color).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x2, y2, z2).setColor(color).setUv(0.0F, 0.0F).setLight(light);
    to.addVertex(x2, y2, z2).setColor(color).setUv(0.0F, 0.0F).setLight(light);
  }

  /** Writes depth and nothing else, so what lies behind a fading letter's nearest surface stays out of sight. */
  static final class Mask implements GeometrySink {

    private final StageBuffers.Pass pass;
    private final RenderType type;
    private VertexConsumer out;

    Mask(StageBuffers.Pass pass, RenderType type) {
      this.pass = pass;
      this.type = type;
    }

    void open() {
      this.out = this.pass.getBuffer(this.type);
    }

    @Override
    public void quad(float x0, float y0, float z0, int color0, float x1, float y1, float z1, int color1, float x2,
        float y2, float z2, int color2, float x3, float y3, float z3, int color3, int light) {
      VertexConsumer to = this.out;
      to.addVertex(x0, y0, z0);
      to.addVertex(x1, y1, z1);
      to.addVertex(x2, y2, z2);
      to.addVertex(x3, y3, z3);
    }

    @Override
    public void triangle(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
        int color, int light) {
      VertexConsumer to = this.out;
      to.addVertex(x0, y0, z0);
      to.addVertex(x1, y1, z1);
      to.addVertex(x2, y2, z2);
      to.addVertex(x2, y2, z2);
    }
  }
}

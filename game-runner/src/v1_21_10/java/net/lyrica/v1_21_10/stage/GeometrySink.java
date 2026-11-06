package net.lyrica.v1_21_10.stage;

/**
 * Where built letter geometry goes. Everything above this line works out positions and colours; everything below it
 * knows one game version's vertex format. Positions are relative to the camera, in world axes. Corners run
 * counter-clockwise as seen from the side that shows.
 */
interface GeometrySink {

  void quad(float x0, float y0, float z0, int color0, float x1, float y1, float z1, int color1, float x2, float y2,
      float z2, int color2, float x3, float y3, float z3, int color3, int light);

  void triangle(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, int color,
      int light);
}

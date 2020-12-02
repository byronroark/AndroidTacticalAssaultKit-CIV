
package com.atakmap.map.layer.feature.geometry.opengl;

import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.Vector2D;
import com.atakmap.lang.Unsafe;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.GeometryFactory;
import com.atakmap.map.layer.feature.geometry.GeometryFactory.ExtrusionHints;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.opengl.GLTriangulate;

import java.nio.DoubleBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements a few utility functions for extruding shapes into 3d objects and outlines.
 */
public class GLExtrude {
    /**
     * Extrude the given shape using a relative altitude. The base of the shape will be baseAltitude
     * and the top will be baseAltitude + extrudeHeight.
     *
     * @param baseAltitude The altitude of the shape's base.
     * @param extrudeHeight The height to extrude to.
     * @param points The shape's points.
     * @param closed True if the shape is closed, false if open
     * @return The extruded shape.
     */
    public static DoubleBuffer extrudeRelative(double baseAltitude,
            double extrudeHeight, GeoPoint[] points, boolean closed) {
        int p = 0;
        double[] tmp = new double[points.length * 3];
        for (int i = 0; i < tmp.length; i += 3) {
            tmp[i] = points[p].getLongitude();
            tmp[i + 1] = points[p].getLatitude();
            tmp[i + 2] = points[p].getAltitude();
            p++;
        }
        DoubleBuffer newPoints = DoubleBuffer.wrap(tmp);
        return extrudeRelative(baseAltitude, extrudeHeight, newPoints, 3,
                closed);
    }

    /**
     * Extrude the given shape using a relative altitude. The base of the shape will be baseAltitude
     * and the top will be baseAltitude + extrudeHeight.
     *
     * @param baseAltitude The altitude of the shape's base.
     * @param extrudeHeight The height to extrude to.
     * @param points The shape's points.
     * @param pointSize The number of components for the shape's points (should only be 2 or 3).
     * @param closed True if the shape is closed, false if open
     * @return The extruded shape.
     */
    public static DoubleBuffer extrudeRelative(double baseAltitude, double extrudeHeight,
            DoubleBuffer points, int pointSize, boolean closed) {
        List<Double> vertices = new ArrayList<>();
        // If we find any self intersections then the polygon needs to be decomposed into simple
        // polygons
        if (closed && checkForSelfIntersection(points, pointSize)) {
            Vector2D[] verts = new Vector2D[points.limit() / pointSize];
            for (int i = 0; i < points.limit(); i += pointSize) {
                    verts[i / pointSize] = new Vector2D(points.get(i), points.get(i + 1));
            }
            List<ArrayList<Double>> polygons = GLTriangulate
                    .extractIntersectingPolygons(verts);
            for (ArrayList<Double> polygon : polygons) {
                LineString ls = new LineString(3);
                for (int i = 0; i < polygon.size() / 2; i++) {
                    ls.addPoint(polygon.get(i * 2), polygon.get((i * 2) + 1), baseAltitude);
                }
                ls.addPoint(polygon.get(0), polygon.get(1), baseAltitude);

                Polygon polylinePolygon = new Polygon(ls);

                vertices.addAll(extrudePolygon(polylinePolygon, extrudeHeight,
                        true));
            }
        } else {
            LineString ls = new LineString(3);
            for (int i = 0; i < points.limit(); i += pointSize) {
                ls.addPoint(points.get(i), points.get(i + 1), baseAltitude);
            }
            if (closed && points.limit() > pointSize) {
                if (points.get(0) != points.get(points.limit() - pointSize) || points.get(1) != points.get(points.limit() - (pointSize - 1))) {
                    ls.addPoint(points.get(0), points.get(1), baseAltitude);
                }
            }

            Polygon polylinePolygon = new Polygon(ls);
            vertices.addAll(extrudePolygon(polylinePolygon, extrudeHeight,
                    closed));
        }
        double[] tmp = new double[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            tmp[i] = vertices.get(i);
        }
        DoubleBuffer extrudedBuffer = com.atakmap.lang.Unsafe.allocateDirect(tmp.length,
                DoubleBuffer.class);
        extrudedBuffer.put(tmp);
        return extrudedBuffer;
    }

    private static boolean checkForSelfIntersection(DoubleBuffer points, int pointSize) {
        List<GLTriangulate.Segment> segments = new ArrayList<>();
        // construct our segment list
        for (int i = 0; i < points.limit(); i += pointSize) {
            int start = i;
            int end = (i + pointSize) % points.limit();
            segments.add(new GLTriangulate.Segment(
                    new Vector2D(points.get(start), points.get(start + 1)),
                    new Vector2D(points.get(end), points.get(end + 1))));
        }
        // check if the segments intersect each other, ignoring the segments that share endpoints
        // since they're guaranteed to intersect
        for (int i = 0; i < segments.size(); i++) {
            GLTriangulate.Segment first = segments.get(i);
            for (int j = i + 1; j < segments.size() + 1; j++) {
                GLTriangulate.Segment second = segments.get(j % segments.size());
                if (first.sharesPoint(second)) {
                    continue;
                }
                Vector2D intersection = Vector2D.segmentToSegmentIntersection(first.start,
                        first.end, second.start, second.end);
                if (intersection != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Double> extrudePolygon(Polygon polygon,
            double extrudeHeight, boolean closed) {
        ExtrusionHints hint = ExtrusionHints.TEEH_None;
        if (!closed)
            hint = ExtrusionHints.TEEH_OmitTopFace;
        Geometry extrudedPolygon = GeometryFactory.extrude(polygon,
                extrudeHeight, hint);
        List<Double> newPoints = new ArrayList<>();
        // Triangulate the polygons that were returned from the extrude() call
        for (Geometry g : ((GeometryCollection) extrudedPolygon).getGeometries()) {
            triangulatePolygon(newPoints, (Polygon) g);
        }
        return newPoints;
    }

    /**
     * Triangulate the given polygon by either passing it to GLTriangulate and extracting the
     * results into verts or by treating it as a triangle fan if it's a convex polygon.
     *
     * @param verts The ArrayList of vertices.
     * @param polygon The polygon that will be triangulated.
     */
    private static void triangulatePolygon(final List<Double> verts, final Polygon polygon) {
        LineString exterior = polygon.getExteriorRing();
        double[] tmp = new double[exterior.getNumPoints() * 3];
        // Don't add the last vertex since it's really the first vertex again
        for (int i = exterior.getNumPoints() - 1; i > -1; i--) {
            Point p = new Point(0, 0, 0);
            exterior.get(p, i);
            tmp[i * 3] = p.getX();
            tmp[(i * 3) + 1] = p.getY();
            tmp[(i * 3) + 2] = p.getZ();
        }
        DoubleBuffer vertBuffer = DoubleBuffer.wrap(tmp);
        ShortBuffer indexBuffer = ShortBuffer.wrap(new short[(exterior.getNumPoints() - 2) * 3]);
        int triangleType = GLTriangulate.triangulate(vertBuffer, 3, exterior.getNumPoints(),
                indexBuffer);
        if (triangleType == GLTriangulate.TRIANGLE_FAN) {
            for (int i = 1; i < exterior.getNumPoints() - 1; i++) {
                verts.add(tmp[0]);
                verts.add(tmp[1]);
                verts.add(tmp[2]);

                verts.add(tmp[i * 3]);
                verts.add(tmp[(i * 3) + 1]);
                verts.add(tmp[(i * 3) + 2]);

                verts.add(tmp[(i + 1) * 3]);
                verts.add(tmp[((i + 1) * 3) + 1]);
                verts.add(tmp[((i + 1) * 3) + 2]);
            }
        } else {
            for (int i = 0; i < indexBuffer.limit(); i++) {
                short index = indexBuffer.get(i);
                verts.add(tmp[index * 3]);
                verts.add(tmp[(index * 3) + 1]);
                verts.add(tmp[(index * 3) + 2]);
            }
        }
    }

    /**
     * Generates a vertex buffer for line segments (GL_LINES) for the outline of an object.
     *
     * @param baseAltitude Altitude of the lower horizontal outline segments.
     * @param extrudeHeight Altitude of the upper horizontal outline segments.
     * @param points Input surface level geometry. Altitude is ignored.
     * @param closed True if the outline is closed, false if open
     * @param simplified True to reduce the amount of vertical lines
     * @return line segment buffer with 3 components per vertex.
     */
    public static DoubleBuffer extrudeOutline(double baseAltitude, double extrudeHeight,
            GeoPoint[] points, boolean closed, boolean simplified) {
        DoubleBuffer newPoints = DoubleBuffer.allocate(points.length * 2);
        for (GeoPoint pt : points) {
            newPoints.put(pt.getLongitude());
            newPoints.put(pt.getLatitude());
        }
        return extrudeOutline(baseAltitude, extrudeHeight, newPoints, 2,
                closed, simplified);
    }

    /**
     * Generates a vertex buffer for line segments (GL_LINES) for the outline of an object.
     *
     * @param baseAltitude Altitude of the lower horizontal outline segments.
     * @param extrudeHeight Altitude of the upper horizontal outline segments.
     * @param points Input surface level geometry.
     * @param pointSize Components per vertex in points. At least 2, altitude is ignored
     * @param closed True if the outline is closed, false if open
     * @param simplified True to reduce the amount of vertical lines
     * @return line segment buffer with 3 components per vertex.
     */
    public static DoubleBuffer extrudeOutline(double baseAltitude,
            double extrudeHeight, DoubleBuffer points, int pointSize,
            boolean closed, boolean simplified) {

        int limit = points.limit();
        if(points.get(0) == points.get(limit - pointSize)
                && points.get(1) == points.get(limit - pointSize + 1)) {
            // ignore last point since it is a duplicate, don't need to generate extra segments
            limit -= pointSize;
            closed = true;
        }

        // Each segment is 2 points, each point maps to 1 vertical segment and 1 horizontal segment
        int numPoints = limit / pointSize;
        int size = numPoints; // Horizontal top segments
        if (!closed)
            size--; // Exclude segment connecting last point to first
        size += simplified ? (closed ? 0 : 2) : numPoints; // Vertical segments
        size *= 6; // Multiply by point size (3) * 2 points per segment
        DoubleBuffer outlineBuffer = Unsafe.allocateDirect(size,
                DoubleBuffer.class);

        // generate horizontal segments
        double extrudedAltitude = baseAltitude + extrudeHeight;
        int lim = limit - (closed ? 0 : pointSize);
        for(int i = 0; i < lim; i += pointSize) {
            outlineBuffer.put(points.get(i));
            outlineBuffer.put(points.get(i + 1));
            outlineBuffer.put(extrudedAltitude);

            outlineBuffer.put(points.get((i + pointSize) % limit));
            outlineBuffer.put(points.get((i + pointSize + 1) % limit));
            outlineBuffer.put(extrudedAltitude);
        }

        // generate vertical segments
        for(int i = 0; i < limit; i += pointSize) {
            // Skip intermediate segments
            if (simplified && (closed || i > 0 && i < limit - pointSize))
                continue;

            outlineBuffer.put(points.get(i));
            outlineBuffer.put(points.get(i + 1));
            outlineBuffer.put(baseAltitude);

            outlineBuffer.put(points.get(i));
            outlineBuffer.put(points.get(i + 1));
            outlineBuffer.put(extrudedAltitude);
        }

        return outlineBuffer;
    }
}

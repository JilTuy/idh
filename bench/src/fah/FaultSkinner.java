/****************************************************************************
Copyright (c) 2014, Colorado School of Mines and others. All rights reserved.
This program and accompanying materials are made available under the terms of
the Common Public License - v1.0, which accompanies this distribution, and is 
available at http://www.eclipse.org/legal/cpl-v10.html
****************************************************************************/
package fah;

import java.util.*;

import edu.mines.jtk.awt.*;
import edu.mines.jtk.dsp.*;
import edu.mines.jtk.util.*;

import static edu.mines.jtk.util.ArrayMath.*;
import static fah.FaultGeometry.*;

/**
 * Computes fault skins from images of fault likelihoods, strikes and dips. A
 * fault skin is a linked list of fault cells. Each fault cell is an oriented
 * point located on a ridge in an image of fault likelihood. Each image sample
 * corresponds to either no cell or one cell.
 * <p>
 * A cell has up to four neighbors ("nabors") that lie above, below, left and
 * right of the cell when viewed from above the fault, that is, when looking
 * from the hanging wall toward the footwall. Links to nabors enables cells to
 * form a skin of connected cells, which represents a fault.
 * <p>
 * Links to left and right cell nabors can be used to iterate over all cells
 * along a fault trace, a path of constant depth that is everywhere tangent to
 * fault strike. Likewise, links to cell nabors above and below a cell can be
 * used to iterate up or down a fault. However, this simple up or down
 * iteration typically does not coincide with a fault curve that is everywhere
 * tangent to fault dip.
 * <p>
 * A fault skin is grown by linking nearby cells having similar properties,
 * beginning with a seed cell that has sufficiently high fault likelihood.
 * Several of the methods in this class enable users to set parameters that
 * control the growing process.
 *
 * @author Dave Hale, Colorado School of Mines
 * @version 2014.07.03
 */
public class FaultSkinner {

  /**
   * Constructs a fault skinner for specified likelihoods and orientations.
   * @param flpt array {fl,fp,ft} of fault likelihoods, strikes and dips.
   */
  public FaultSkinner(float[][][][] flpt) {
    _fl = flpt[0];
    _fp = flpt[1];
    _ft = flpt[2];
    _n1 = _fl[0][0].length;
    _n2 = _fl[0].length;
    _n3 = _fl.length;
    _fllo = 0.2f;
    _flhi = 0.8f;
    _dflmax = 0.2f;
    _dfpmax = 30.0f;
    _dftmax = 10.0f;
    _dnpmax = 0.5f;
    _ncsmin = 400;
  }

  /**
   * Sets fault likelihood thresholds used to grow skins. Cells in a skin
   * should have, or be connected to cells that have, high fault likelihoods.
   * All cells in a skin will have fault likelihoods not less than the lower
   * threshold. At least one cell in a skin will have a fault likelihood not
   * less than the upper threshold. 
   * <p>
   * The default thresholds are 0.2 and 0.8, respectively.
   * @param lowerLikelihood lower threshold for fault likelihood.
   * @param upperLikelihood upper threshold for fault likelihood.
   */
  public void setGrowLikelihoods(
      double lowerLikelihood, double upperLikelihood) {
    Check.argument(lowerLikelihood<=upperLikelihood,
        "lowerLikelihood does not exceed upperLikelihood");
    _fllo = (float)lowerLikelihood;
    _flhi = (float)upperLikelihood;
  }

  /**
   * Sets thresholds for variations in fault likelihood, strike and dip. A
   * cell and its nabors should have similar fault properties. The specified
   * thresholds are maximum differences permitted between a cell and its
   * nabors.
   * <p>
   * The default limits are 0.1, 30 degrees, and 10 degrees, respectively.
   * @param maxDeltaLikelihood upper bound on changes in fault likelihood.
   * @param maxDeltaStrike upper bound for changes in fault strike, in degrees.
   * @param maxDeltaDip upper bound for changes in fault dip, in degrees.
   */
  public void setMaxDeltas(
      double maxDeltaLikelihood, double maxDeltaStrike, double maxDeltaDip) {
    _dflmax = (float)maxDeltaLikelihood;
    _dfpmax = (float)maxDeltaStrike;
    _dftmax = (float)maxDeltaDip;
  }

  /**
   * Sets the threshold planar distance. A cell should lie near the planes of
   * nabor cells. The specified threshold is the maximum distance to nabor
   * planes.
   * <p>
   * The default maximim planar distance is 0.5 samples.
   * @param maxPlanarDistance upper bound on planar distance, in samples.
   */
  public void setMaxPlanarDistance(double maxPlanarDistance) {
    _dnpmax = (float)maxPlanarDistance;
  }

  /**
   * Sets the minimum number of cells in a skin.
   * <p>
   * The default minimum skin size is 400.
   */
  public void setMinSkinSize(int minSize) {
    _ncsmin = minSize;
  }

  /**
   * Returns array of cells in ridge surfaces of fault likelihood.
   * @return array of cells.
   */
  public FaultCell[] findCells() {
    return cells();
  }

  /**
   * Returns an array of skins comprised of specified cells. Some cells may be
   * unused. For example, cells with fault likelihoods less than the lower
   * threshold for growing skins will be unused. Likewise, cells that do not
   * form a skin with sufficient size will be unused.
   * @param cells array of cells from which to grow skins.
   * @return array of skins.
   */
  public FaultSkin[] findSkins(FaultCell[] cells) {
    return skins(cells);
  }

  /**
   * Gets arrays {xyz,uvw,rgb} of cell coordinates, normals and colors.
   * In these arrays, cells are represented by quads with specified size.
   * @param size the size (in samples) of the quads.
   * @param cells the cells for which to compute quads.
   */
  public static float[][] getXyzUvwRgb(float size, FaultCell[] cells) {
    FloatList xyz = new FloatList();
    FloatList uvw = new FloatList();
    FloatList fcl = new FloatList();
    size *= 0.5f;
    float[] qa = {0.0f,-size,-size};
    float[] qb = {0.0f, size,-size};
    float[] qc = {0.0f, size, size};
    float[] qd = {0.0f,-size, size};
    for (FaultCell cell:cells) {
      float x1 = cell.x1;
      float x2 = cell.x2;
      float x3 = cell.x3;
      float w1 = cell.w1;
      float w2 = cell.w2;
      float w3 = cell.w3;
      float fl = cell.fl;
      float fp = toRadians(cell.fp);
      float ft = toRadians(cell.ft);
      float cp = cos(fp);
      float sp = sin(fp);
      float ct = cos(ft);
      float st = sin(ft);
      float[] ra = rotatePoint(cp,sp,ct,st,qa);
      float[] rb = rotatePoint(cp,sp,ct,st,qb);
      float[] rc = rotatePoint(cp,sp,ct,st,qc);
      float[] rd = rotatePoint(cp,sp,ct,st,qd);
      float a1 = x1+ra[0], a2 = x2+ra[1], a3 = x3+ra[2];
      float b1 = x1+rb[0], b2 = x2+rb[1], b3 = x3+rb[2];
      float c1 = x1+rc[0], c2 = x2+rc[1], c3 = x3+rc[2];
      float d1 = x1+rd[0], d2 = x2+rd[1], d3 = x3+rd[2];
      xyz.add(a3); xyz.add(a2); xyz.add(a1); fcl.add(fl);
      xyz.add(b3); xyz.add(b2); xyz.add(b1); fcl.add(fl);
      xyz.add(c3); xyz.add(c2); xyz.add(c1); fcl.add(fl);
      xyz.add(d3); xyz.add(d2); xyz.add(d1); fcl.add(fl);
      uvw.add(w3); uvw.add(w2); uvw.add(w1);
      uvw.add(w3); uvw.add(w2); uvw.add(w1);
      uvw.add(w3); uvw.add(w2); uvw.add(w1);
      uvw.add(w3); uvw.add(w2); uvw.add(w1);
    }
    float[] fc = fcl.trim();
    float fcmin = 0.0f;
    float fcmax = 1.0f;
    ColorMap cmap = new ColorMap(fcmin,fcmax,ColorMap.JET);
    float[] rgb = cmap.getRgbFloats(fc);
    return new float[][]{xyz.trim(),uvw.trim(),rgb};
  }

  ///////////////////////////////////////////////////////////////////////////
  // private

  private int _n1,_n2,_n3; // dimensions of fault images fl, fp and ft
  private float[][][] _fl,_fp,_ft; // fault likelihoods, strikes and dips
  private float _fllo; // lower threshold on fault likelihoods
  private float _flhi; // higher threshold on fault likelihoods
  private float _dflmax; // max difference between likelihoods of nabors
  private float _dfpmax; // max difference between strikes of nabors
  private float _dftmax; // max difference between dips of nabors
  private float _dnpmax; // max distance to planes of nabors
  private int _ncsmin; // min number of cells that form a skin
  private static Comparator<FaultCell> _cellComparator = 
      new Comparator<FaultCell>() {
    public int compare(FaultCell c1, FaultCell c2) {
      if (c1.fl<c2.fl)
        return -1;
      else if (c1.fl>c2.fl)
        return 1;
      else
        return 0;
    }
  };

  // Uses fault images to find cells, oriented points located on ridges.
  private FaultCell[] cells() {
    int n1 = _n1, n2 = _n2, n3 = _n3;
    float[][][] f = _fl, p = _fp, t = _ft;

    // Smooth fault likelihoods in 2nd and 3rd dimensions. This helps to
    // eliminate spurious ridges, and improves the accuracy of 2nd-order
    // finite-difference approximations (parabolic interpolation) used to
    // locate ridges.
    float[][][] fs = new float[n3][n2][n1];
    RecursiveGaussianFilter rgf = new RecursiveGaussianFilter(1.0);
    rgf.applyX0X(f,fs);
    rgf.applyXX0(fs,fs);
    f = fs;

    // Vertical image boundaries are discontinuities that may look like
    // faults. If a fault appears to be near and nearly parallel to image
    // boundaries, then assume it is a boundary artifact and not truly a
    // fault.
    int imax = 5; // max number of samples considered to be near boundary
    float wwmax = 0.75f; // cosine of 30 degrees, squared

    // Loop over all samples. Construct cells for samples nearest to ridges.
    ArrayList<FaultCell> cellList = new ArrayList<FaultCell>();
    for (int i3=0; i3<n3; ++i3) {
      int i3m = max(i3-1,0);
      int i3p = min(i3+1,n3-1);
      for (int i2=0; i2<n2; ++i2) {
        int i2m = max(i2-1,0);
        int i2p = min(i2+1,n2-1);
        float[] fmi = f[i3m][i2 ];
        float[] fim = f[i3 ][i2m];
        float[] fip = f[i3 ][i2p];
        float[] fpi = f[i3p][i2 ];
        float[] fmm = f[i3m][i2m];
        float[] fpp = f[i3p][i2p];
        float[] fmp = f[i3m][i2p];
        float[] fpm = f[i3p][i2m];
        float[] fii = f[i3 ][i2 ];
        float[] pii = p[i3 ][i2 ];
        float[] tii = t[i3 ][i2 ];
        for (int i1=0; i1<n1; ++i1) {
          float fmii = fmi[i1 ];
          float fimi = fim[i1 ];
          float fipi = fip[i1 ];
          float fpii = fpi[i1 ];
          float fmmi = fmm[i1 ];
          float fppi = fpp[i1 ];
          float fmpi = fmp[i1 ];
          float fpmi = fpm[i1 ];
          float fiii = fii[i1 ];
          float piii = pii[i1 ];
          float tiii = tii[i1 ];

          // Most image samples will not have a fault cell.
          FaultCell cell = null;

          // Accumulators for ridge likelihoods and locations. Depending on
          // the limits on fault strike used below, we may find more than one
          // ridge.
          float nr = 0;
          float fl = 0.0f;
          float d2 = 0.0f;
          float d3 = 0.0f;

          // If S-N ridge, ...
          if ((fipi<fiii && fimi<fiii) &&
              ((337.5f<=piii || piii<= 22.5f) || 
               (157.5f<=piii && piii<=202.5f))) {
            float f1 = 0.5f*(fipi-fimi); // 1st derivative
            float f2 = fipi-2.0f*fiii+fimi; // 2nd derivative
            float dr = -f1/f2; // signed distance to ridge
            float fr = fiii+f1*dr+0.5f*f2*dr*dr; // fault likelihood
            if (fr>=_fllo) {
              float[] w = faultNormalVectorFromStrikeAndDip(piii,tiii);
              float w1 = w[0], w2 = w[1], w3 = w[2];
              if (imax<=i2 && i2<_n2-imax || w2*w2<=wwmax) {
                fl += fr;
                d2 += dr;
                nr += 1;
              }
            }
          }

          // If SW-NE ridge, ...
          if ((fmpi<fiii && fpmi<fiii) &&
              (( 22.5f<=piii && piii<= 67.5f) || 
               (202.5f<=piii && piii<=247.5f))) {
            float f1 = 0.5f*(fmpi-fpmi); // 1st derivative
            float f2 = fmpi-2.0f*fiii+fpmi; // 2nd derivative
            float dr = -f1/f2; // signed distance to ridge
            float fr = fiii+f1*dr+0.5f*f2*dr*dr; // fault likelihood
            if (fr>=_fllo) {
              float[] w = faultNormalVectorFromStrikeAndDip(piii,tiii);
              float w1 = w[0], w2 = w[1], w3 = w[2];
              if ((imax<=i2 && i2<_n2-imax || w2*w2<=wwmax) &&
                  (imax<=i3 && i3<_n3-imax || w3*w3<=wwmax)) {
                fl += fr;
                d2 += dr;
                d3 -= dr;
                nr += 1;
              }
            }
          }

          // If W-E ridge, ...
          if ((fpii<fiii && fmii<fiii) &&
              (( 67.5f<=piii && piii<=112.5f) ||
               (247.5f<=piii && piii<=292.5f))) {
            float f1 = 0.5f*(fpii-fmii); // 1st derivative
            float f2 = fmii-2.0f*fiii+fpii; // 2nd derivative
            float dr = -f1/f2; // signed distance to ridge
            float fr = fiii+f1*dr+0.5f*f2*dr*dr; // fault likelihood
            if (fr>=_fllo) {
              float[] w = faultNormalVectorFromStrikeAndDip(piii,tiii);
              float w1 = w[0], w2 = w[1], w3 = w[2];
              if (imax<=i3 && i3<_n3-imax || w3*w3<=wwmax) {
                fl += fr;
                d3 += dr;
                nr += 1;
              }
            }
          }

          // If NW-SE ridge, ...
          if ((fppi<fiii && fmmi<fiii) &&
              ((112.5f<=piii && piii<=157.5f) || 
               (292.5f<=piii && piii<=337.5f))) {
            float f1 = 0.5f*(fppi-fmmi); // 1st derivative
            float f2 = fppi-2.0f*fiii+fmmi; // 2nd derivative
            float dr = -f1/f2; // signed distance to ridge
            float fr = fiii+f1*dr+0.5f*f2*dr*dr; // fault likelihood
            if (fr>=_fllo) {
              float[] w = faultNormalVectorFromStrikeAndDip(piii,tiii);
              float w1 = w[0], w2 = w[1], w3 = w[2];
              if ((imax<=i2 && i2<_n2-imax || w2*w2<=wwmax) &&
                  (imax<=i3 && i3<_n3-imax || w3*w3<=wwmax)) {
                fl += fr;
                d2 += dr;
                d3 += dr;
                nr += 1;
              }
            }
          }

          // If at least one ridge, construct a cell and add to list.
          if (nr>0) {
            fl /= nr;
            d2 /= nr;
            d3 /= nr;
            cell = new FaultCell(i1,i2+d2,i3+d3,fl,piii,tiii);
            cellList.add(cell);
          }
        }
      }
    }
    return cellList.toArray(new FaultCell[0]);
  }

  // Returns skins constructed from specified cells.
  private FaultSkin[] skins(FaultCell[] cells) {
    int ncell = cells.length;

    // Grid of cells used to quickly find cell nabors.
    FaultCellGrid cellGrid = new FaultCellGrid(_n1,_n2,_n3,cells);

    // Empty list of skins.
    ArrayList<FaultSkin> skinList = new ArrayList<FaultSkin>();

    // Sort array of cells by increasing fault likelihoods.
    FaultCell[] temp = new FaultCell[ncell];
    System.arraycopy(cells,0,temp,0,ncell);
    cells = temp;
    Arrays.sort(cells,_cellComparator);

    // While cells with sufficiently high fault likelihood remain, ...
    for (int kseed=ncell-1; kseed>=0; --kseed) {

      // Skip cells with low fault likelihood or that are already in a skin.
      while (kseed>=0 && (cells[kseed].fl<_flhi || cells[kseed].skin!=null))
        --kseed;

      // If we found a cell with which to construct a new skin, ...
      if (kseed>=0) {

        // Make a new empty skin.
        FaultSkin skin = new FaultSkin();

        // Make a new sorted set of cells, initially with only the seed cell.
        TreeSet<FaultCell> growSet = new TreeSet<FaultCell>(_cellComparator);
        growSet.add(cells[kseed]);

        // While the grow set is not empty, ...
        while (!growSet.isEmpty()) {

          // Get the cell with highest fault likelihood, while removing it
          // from the grow set, and add it to the skin.
          FaultCell cell = growSet.pollLast();
          skin.add(cell);

          // Link mutually best nabors, and add them to the grow set.
          FaultCell ca,cb,cl,cr;
          ca = findNaborAbove(cellGrid,cell);
          cb = findNaborBelow(cellGrid,ca);
          if (ca!=null && ca.skin==null && cb==cell) {
            linkAboveBelow(ca,cb);
            growSet.add(ca);
          }
          cb = findNaborBelow(cellGrid,cell);
          ca = findNaborAbove(cellGrid,cb);
          if (cb!=null && cb.skin==null && ca==cell) {
            linkAboveBelow(ca,cb);
            growSet.add(cb);
          }
          cl = findNaborLeft(cellGrid,cell);
          cr = findNaborRight(cellGrid,cl);
          if (cl!=null && cl.skin==null && cr==cell) {
            linkLeftRight(cl,cr);
            growSet.add(cl);
          }
          cr = findNaborRight(cellGrid,cell);
          cl = findNaborLeft(cellGrid,cr);
          if (cr!=null && cr.skin==null && cl==cell) {
            linkLeftRight(cl,cr);
            growSet.add(cr);
          }
        }

        // Done growing. Add this skin to the list.
        skinList.add(skin);
      }
    }

    // Copy skins with sufficient size to a list of big skins to be returned.
    // Make skinless all cells contained in any smaller skins.
    ArrayList<FaultSkin> bigSkinList = new ArrayList<FaultSkin>();
    for (FaultSkin skin:skinList) {
      if (skin.size()>=_ncsmin) {
        bigSkinList.add(skin);
      } else {
        for (FaultCell cell:skin.getCellList())
          cell.skin = null;
      }
    }
    return bigSkinList.toArray(new FaultSkin[0]);
  }

  // Returns true if the specified cells are nabors. This method assumes that
  // all links are mutual. For example, if c1 is the nabor above c2, then c2
  // must be the nabor below c1.
  private static boolean areNabors(FaultCell c1, FaultCell c2) {
    return c1.ca==c2 || c1.cb==c2 || c1.cl==c2 || c1.cr==c2;
  }

  // Methods to link mutually best nabors.
  private void linkAboveBelow(FaultCell ca, FaultCell cb) {
    cb.ca = ca;
    ca.cb = cb;
  }
  private void linkLeftRight(FaultCell cl, FaultCell cr) {
    cr.cl = cl;
    cl.cr = cr;
  }

  // Methods to find good nabors of a specified cell. These methods return
  // null if no nabor is good enough, based on various thresholds.
  private FaultCell findNaborAbove(FaultCellGrid cells, FaultCell cell) {
    FaultCell ca = cells.findCellAbove(cell);
    return canBeNabors(cell,ca)?ca:null;
  }
  private FaultCell findNaborBelow(FaultCellGrid cells, FaultCell cell) {
    FaultCell cb = cells.findCellBelow(cell);
    return canBeNabors(cell,cb)?cb:null;
  }
  private FaultCell findNaborLeft(FaultCellGrid cells, FaultCell cell) {
    FaultCell cl = cells.findCellLeft(cell);
    return canBeNabors(cell,cl)?cl:null;
  }
  private FaultCell findNaborRight(FaultCellGrid cells, FaultCell cell) {
    FaultCell cr = cells.findCellRight(cell);
    return canBeNabors(cell,cr)?cr:null;
  }

  // Returns true if two specified cells can be nabors. The two cells are
  // assumed to be within one sample of each other. This method uses other
  // attributes of the cells to determine whether or not they can be nabors.
  private boolean canBeNabors(FaultCell ca, FaultCell cb) {
    boolean can = true;
    if (ca==null || cb==null) {
      can = false;
    } else if (minFl(ca,cb)<_fllo) {
      can = false;
    } else if (absDeltaFl(ca,cb)>_dflmax) {
      can = false;
    } else if (absDeltaFp(ca,cb)>_dfpmax) {
      can = false;
    } else if (absDeltaFt(ca,cb)>_dftmax) {
      can = false;
    } else if (maxDistanceToPlane(ca,cb)>_dnpmax) {
      can = false;
    }
    return can;
  }
  private static float minFl(FaultCell ca, FaultCell cb) {
    return min(ca.fl,cb.fl);
  }
  private static float absDeltaFl(FaultCell ca, FaultCell cb) {
    return abs(ca.fl-cb.fl);
  }
  private static float absDeltaFp(FaultCell ca, FaultCell cb) {
    float del = ca.fp-cb.fp;
    return min(abs(del),abs(del+360.0f),abs(del-360.0f));
  }
  private static float absDeltaFt(FaultCell ca, FaultCell cb) {
    return abs(ca.ft-cb.ft);
  }
  private static float maxDistanceToPlane(FaultCell ca, FaultCell cb) {
    float aw1 = ca.w1, aw2 = ca.w2, aw3 = ca.w3;
    float ax1 = ca.x1, ax2 = ca.x2, ax3 = ca.x3;
    float bw1 = cb.w1, bw2 = cb.w2, bw3 = cb.w3;
    float bx1 = cb.x1, bx2 = cb.x2, bx3 = cb.x3;
    float dx1 = ax1-bx1;
    float dx2 = ax2-bx2;
    float dx3 = ax3-bx3;
    float dab = aw1*dx1+aw2*dx2+aw3*dx3;
    float dba = bw1*dx1+bw2*dx2+bw3*dx3;
    return max(dab,dba);
  }

  // Rotates a specified point by strike (phi) and dip (theta) angles,
  // given specified cosines (cp and ct) and sines (sp and st) of those 
  // angles. The order of transformation is
  // (1) rotate around axis x3 by dip angle
  // (2) rotate around axis x1 by strike angle
  // Returns the coordinates of the rotated point.
  private static float[] rotatePoint(
      float cp, float sp, float ct, float st, float[] x) {
    float x1 = x[0], x2 = x[1], x3 = x[2];
    float y1 =     ct*x1+   st*x2;
    float y2 = -cp*st*x1+cp*ct*x2+sp*x3;
    float y3 =  sp*st*x1-sp*ct*x2+cp*x3;
    return new float[]{y1,y2,y3};
  }

  private static void trace(String s) {
    System.out.println(s);
  }

  private static class FloatList {
    public int n;
    public float[] a = new float[1024];
    public void add(float f) {
      if (n==a.length) {
        float[] t = new float[2*n];
        System.arraycopy(a,0,t,0,n);
        a = t;
      }
      a[n++] = f;
    }
    public void add(float[] f) {
      int m = f.length;
      for (int i=0; i<m; ++i)
        add(f[i]);
    }
    public float[] trim() {
      if (n==0)
        return null;
      float[] t = new float[n];
      System.arraycopy(a,0,t,0,n);
      return t;
    }
  }
}

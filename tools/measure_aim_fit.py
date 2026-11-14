import cv2, math, numpy as np

ASSETS = "/home/hatch/workspace/production/8ball-pool-rea/guideline-tool/app/src/main/assets/demo"
# felt rect (table ROI) from generate_demo_images.py
ROI = (80, 90, 1200, 630)

def dist_to_seg(px, py, ax, ay, bx, by):
    dx, dy = bx-ax, by-ay
    ls = dx*dx+dy*dy
    if ls < 1e-9: return math.hypot(px-ax, py-ay)
    t = ((px-ax)*dx+(py-ay)*dy)/ls
    t = max(0.0, min(1.0, t))
    return math.hypot(px-(ax+t*dx), py-(ay+t*dy))

def tukey_refit(seg, inliers):
    # inliers: list of (x,y); weights from dist to seg / 1.5, Tukey (1-u^2)^2
    (ax, ay), (bx, by) = seg
    w = []
    for (px, py) in inliers:
        u = dist_to_seg(px, py, ax, ay, bx, by) / 1.5
        w.append(0.0 if u >= 1.0 else (1-u*u)**2)
    ws = sum(w)
    if ws < 1e-9: return seg
    mx = sum(wi*px for wi,(px,py) in zip(w,inliers))/ws
    my = sum(wi*py for wi,(px,py) in zip(w,inliers))/ws
    sxx = sxy = syy = 0.0
    for wi,(px,py) in zip(w,inliers):
        dx, dy = px-mx, py-my
        sxx += wi*dx*dx; sxy += wi*dx*dy; syy += wi*dy*dy
    theta = 0.5*math.atan2(2*sxy, sxx-syy)
    dirx, diry = math.cos(theta), math.sin(theta)
    segdx, segdy = bx-ax, by-ay
    sl = math.hypot(segdx, segdy); segdx/=sl; segdy/=sl
    if dirx*segdx+diry*segdy < 0: dirx, diry = -dirx, -diry
    ts = [ (px-mx)*dirx+(py-my)*diry for (px,py) in inliers ]
    tmin, tmax = min(ts), max(ts)
    ref = ((mx+dirx*tmin, my+diry*tmin), (mx+dirx*tmax, my+diry*tmax))
    ang = math.degrees(math.acos(max(-1,min(1, segdx*dirx+segdy*diry))))
    refl = math.hypot(ref[1][0]-ref[0][0], ref[1][1]-ref[0][1])
    if ang > 5.0 or refl < sl*0.5: return seg
    return ref

def measure(path, cue, target, pocket, scale=1.0):
    img = cv2.imread(path)
    if scale != 1.0:
        img = cv2.resize(img, (int(img.shape[1]*scale), int(img.shape[0]*scale)))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    x0,y0,x1,y1 = [int(v*scale) for v in ROI]
    roi = gray[y0:y1, x0:x1]
    blur = cv2.bilateralFilter(roi, 20, 200, 1)
    edges = cv2.Canny(blur, 50, 150)
    lines = cv2.HoughLinesP(edges, 1, math.pi/180, 30, minLineLength=15, maxLineGap=8)
    pts = cv2.findNonZero(edges)
    pts = pts.reshape(-1,2) if pts is not None else np.zeros((0,2))
    step = len(pts)//8000 if len(pts) > 8000 else 1
    pts = pts[::step]
    cands = []
    if lines is not None:
        arr = lines.reshape(-1, 4)
        for l in arr:
            cands.append(((float(l[0]),float(l[1])),(float(l[2]),float(l[3]))))
    # dedup + verify
    seen = set(); uniq = []
    for s in cands:
        k = (int(s[0][0]),int(s[0][1]),int(s[1][0]),int(s[1][1]))
        if k not in seen: seen.add(k); uniq.append(s)
    scored = []
    for s in uniq:
        inl = [(float(px),float(py)) for (px,py) in pts
               if dist_to_seg(px,py,s[0][0],s[0][1],s[1][0],s[1][1]) <= 1.5]
        if len(inl) > 20: scored.append((len(inl), s, inl))
    scored.sort(key=lambda t: -t[0])
    kept = [tukey_refit(s, inl) for (_, s, inl) in scored[:3]]
    # true aim + drawn chord (both in ROI coords, scale applied)
    def sc(p): return (p[0]*scale, p[1]*scale)
    cue, target, pocket = sc(cue), sc(target), sc(pocket)
    r = 16*scale
    dx, dy = pocket[0]-target[0], pocket[1]-target[1]
    d = math.hypot(dx,dy); ux, uy = dx/d, dy/d
    gx, gy = target[0]-2*r*ux, target[1]-2*r*uy
    ax, ay = gx-cue[0], gy-cue[1]; ad = math.hypot(ax,ay); ax/=ad; ay/=ad
    true_ang = math.degrees(math.atan2(ay, ax))
    # drawn line (v1.0.28): cue edge -> foot of perpendicular, pulled
    # back along the ray so it stays r+2 clear of the target ball.
    # the true aim ray, not the old cue-edge -> target-edge chord
    c0 = (cue[0]+ax*(r+2), cue[1]+ay*(r+2))
    foot_dist = (target[0]-cue[0])*ax + (target[1]-cue[1])*ay
    fx, fy = cue[0]+ax*foot_dist, cue[1]+ay*foot_dist
    foot_perp = math.hypot(target[0]-fx, target[1]-fy)
    end_dist = foot_dist
    if foot_perp < r+2:
        end_dist = foot_dist - math.sqrt((r+2)**2 - foot_perp**2)
    c1 = (cue[0]+ax*end_dist, cue[1]+ay*end_dist)
    chord_ang = math.degrees(math.atan2(c1[1]-c0[1], c1[0]-c0[0]))
    out = {"true_aim_deg": true_ang, "drawn_chord_deg": chord_ang,
           "chord_vs_aim_deg": chord_ang-true_ang, "n_candidates": len(cands),
           "n_verified": len(scored)}
    if kept:
        best = max(kept, key=lambda s: math.hypot(s[1][0]-s[0][0], s[1][1]-s[0][1]))
        fa = math.degrees(math.atan2(best[1][1]-best[0][1], best[1][0]-best[0][0]))
        # normalise angle diff to [-90,90]
        def d180(a,b):
            dd = (a-b+90)%180-90
            return dd
        out["fitted_deg"] = fa
        out["fit_vs_chord_deg"] = d180(fa, chord_ang)
        out["fit_vs_true_aim_deg"] = d180(fa, true_ang)
        out["fitted_len_px"] = math.hypot(best[1][0]-best[0][0], best[1][1]-best[0][1])
        out["top_inliers"] = scored[0][0]
    return out

print("GREEN full-res (1280x720, r=16):")
print(measure(f"{ASSETS}/demo_table_green.png", (350,360),(700,360),(1200,90)))
print("BLUE full-res (1280x720, r=16):")
print(measure(f"{ASSETS}/demo_table_blue.png", (300,500),(800,200),(80,90)))
print("GREEN working-res (720 wide, r=9):")
print(measure(f"{ASSETS}/demo_table_green.png", (350,360),(700,360),(1200,90), scale=720/1280))
print("BLUE working-res (720 wide, r=9):")
print(measure(f"{ASSETS}/demo_table_blue.png", (300,500),(800,200),(80,90), scale=720/1280))

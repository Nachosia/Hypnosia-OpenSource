import { useEffect, useRef } from 'react';
import * as skinview3d from 'skinview3d';

interface Props {
  size?: number;
  skinUrl?: string;
  skinModel?: 'classic' | 'slim';
}

const STEVE_UUID = 'fe008fc7387e4477a8260219bd8c0c13';

export default function SkinViewer3D({ size = 300, skinUrl, skinModel = 'classic' }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewerRef = useRef<skinview3d.SkinViewer | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    // Skin source: custom uploaded skin if set, otherwise the default Steve skin.
    const resolvedSkinUrl = skinUrl ?? `https://mc-heads.net/skin/${STEVE_UUID}`;

    const viewer = new skinview3d.SkinViewer({
      canvas,
      width: size,
      height: Math.round(size * 1.33),
      skin: resolvedSkinUrl,
      model: skinModel === 'slim' ? 'slim' : 'default',
      preserveDrawingBuffer: true,
    });

    // Controls — exactly like NameMC Extras
    viewer.controls.enableRotate = true;
    viewer.controls.enableZoom = false;
    viewer.controls.enablePan = false;

    // Camera — exactly like NameMC Extras
    viewer.fov = 38;
    viewer.camera.position.y = 22;
    viewer.camera.position.z = 57;

    // Player rotation — NameMC Extras initial angle
    viewer.playerWrapper.rotation.y = 0.53;

    // Lighting — balanced for dark background
    viewer.globalLight.intensity = 2.5;
    viewer.cameraLight.intensity = 0.85;
    viewer.cameraLight.position.set(12, 25, 0);

    // Zoom
    viewer.zoom = 0.86;

    // Animation — Walking like NameMC, speed 0.5, no head bobbing
    viewer.animation = new skinview3d.WalkingAnimation();
    viewer.animation.speed = 0.5;
    viewer.animation.headBobbing = false;

    // No background, no name tag
    viewer.background = null;
    viewer.nameTag = null;

    viewerRef.current = viewer;

    return () => {
      viewer.dispose();
      viewerRef.current = null;
    };
  }, [size, skinUrl, skinModel]);

  return (
    <div
      className="relative overflow-hidden"
      style={{
        width: '100%',
        maxWidth: size,
        borderRadius: 16,
        background: 'radial-gradient(circle at 50% 30%, rgba(74, 121, 255, 0.22), transparent 50%), #161b26',
        border: '1px solid rgba(255, 255, 255, 0.08)',
        boxShadow: '0 20px 60px rgba(0, 0, 0, 0.35)',
      }}
    >
      <canvas
        ref={canvasRef}
        style={{
          width: '100%',
          height: '100%',
          display: 'block',
          borderRadius: 14,
          aspectRatio: `${size} / ${Math.round(size * 1.33)}`,
        }}
      />
    </div>
  );
}

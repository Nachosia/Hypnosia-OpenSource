import { useEffect, useRef } from 'react';
import * as skinview3d from 'skinview3d';

interface Props {
  size?: number;
  skinUrl?: string;
  skinModel?: 'classic' | 'slim';
  className?: string;
  style?: React.CSSProperties;
}

const STEVE_UUID = 'fe008fc7387e4477a8260219bd8c0c13';

export default function SkinPreview3D({
  size = 300,
  skinUrl,
  skinModel = 'classic',
  className,
  style,
}: Props) {
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

    viewer.controls.enableRotate = true;
    viewer.controls.enableZoom = false;
    viewer.controls.enablePan = false;

    viewer.fov = 38;
    viewer.camera.position.y = 22;
    viewer.camera.position.z = 57;

    viewer.playerWrapper.rotation.y = 0.53;

    viewer.globalLight.intensity = 2.5;
    viewer.cameraLight.intensity = 0.85;
    viewer.cameraLight.position.set(12, 25, 0);

    viewer.zoom = 0.86;

    viewer.animation = new skinview3d.WalkingAnimation();
    viewer.animation.speed = 0.5;
    viewer.animation.headBobbing = false;

    viewer.background = null;
    viewer.nameTag = null;

    viewerRef.current = viewer;

    return () => {
      viewer.dispose();
      viewerRef.current = null;
    };
  }, [size, skinUrl, skinModel]);

  return (
    <canvas
      ref={canvasRef}
      className={className}
      style={{
        width: size,
        height: Math.round(size * 1.33),
        display: 'block',
        ...style,
      }}
    />
  );
}

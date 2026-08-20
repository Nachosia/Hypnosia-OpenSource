declare module 'skinview3d' {
  export class SkinViewer {
    constructor(options: {
      canvas: HTMLCanvasElement;
      width?: number;
      height?: number;
      skin?: string | null;
      cape?: string | null;
      preserveDrawingBuffer?: boolean;
    });
    fov: number;
    zoom: number;
    autoRotate: boolean;
    animation: any;
    background: number | null;
    nameTag: string | null;
    globalLight: { intensity: number };
    cameraLight: { intensity: number; position: { set(x: number, y: number, z: number): void } };
    camera: { position: { y: number; z: number } };
    playerWrapper: { rotation: { y: number } };
    controls: { enableRotate: boolean; enableZoom: boolean; enablePan: boolean };
    playerObject: {
      skin: Record<string, { outerLayer: { visible: boolean }; rotation: { x: number } }>;
      cape: { rotation: { x: number } };
    };
    width: number;
    height: number;
    canvas: HTMLCanvasElement;
    loadSkin(url: string, options?: { model?: string; ears?: boolean }): Promise<void>;
    loadCape(url: string | null, options?: { backEquipment?: string }): Promise<void>;
    dispose(): void;
  }
  export class IdleAnimation { speed: number; paused: boolean; }
  export class WalkingAnimation { speed: number; paused: boolean; headBobbing: boolean; }
  export class RunningAnimation { speed: number; }
  export class FlyingAnimation { speed: number; }
}

import { useEffect, useRef } from 'react';

/*
  Living Constellation Background
  - Drifting particles in #80FF97 (green) and #6BB7FF (blue)
  - Connection lines between nearby particles
  - Mouse-reactive: cursor attracts particles
  - Floating gradient orbs on the back layer
*/

export default function DynamicBackground() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const frameRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let W = window.innerWidth;
    let H = window.innerHeight;
    canvas.width = W;
    canvas.height = H;

    const COLORS = [
      { r: 128, g: 255, b: 151 },  // #80FF97 green
      { r: 107, g: 183, b: 255 },  // #6BB7FF blue
      { r: 128, g: 255, b: 220 },  // blend
      { r: 180, g: 220, b: 255 },  // light blue
    ];

    const PARTICLE_COUNT = Math.min(120, Math.floor((W * H) / 12000));
    const CONNECTION_DIST = 140;
    const MOUSE_RADIUS = 200;

    let mouseX = W / 2;
    let mouseY = H / 2;
    let mouseActive = false;

    interface Particle {
      x: number;
      y: number;
      vx: number;
      vy: number;
      radius: number;
      color: typeof COLORS[0];
      alpha: number;
      pulseSpeed: number;
      pulseOffset: number;
    }

    const particles: Particle[] = [];

    for (let i = 0; i < PARTICLE_COUNT; i++) {
      const color = COLORS[Math.floor(Math.random() * COLORS.length)];
      particles.push({
        x: Math.random() * W,
        y: Math.random() * H,
        vx: (Math.random() - 0.5) * 0.4,
        vy: (Math.random() - 0.5) * 0.4,
        radius: Math.random() * 1.8 + 0.8,
        color,
        alpha: Math.random() * 0.5 + 0.3,
        pulseSpeed: Math.random() * 0.02 + 0.01,
        pulseOffset: Math.random() * Math.PI * 2,
      });
    }

    // Large floating orbs (background layer)
    interface Orb {
      x: number;
      y: number;
      vx: number;
      vy: number;
      radius: number;
      color: string;
      alpha: number;
    }

    const orbs: Orb[] = [
      { x: W * 0.2, y: H * 0.3, vx: 0.15, vy: -0.1, radius: 250, color: '#80FF97', alpha: 0.04 },
      { x: W * 0.8, y: H * 0.6, vx: -0.12, vy: 0.08, radius: 300, color: '#6BB7FF', alpha: 0.035 },
      { x: W * 0.5, y: H * 0.8, vx: 0.08, vy: -0.15, radius: 200, color: '#80FF97', alpha: 0.03 },
      { x: W * 0.1, y: H * 0.7, vx: 0.1, vy: 0.12, radius: 180, color: '#6BB7FF', alpha: 0.025 },
    ];

    const onMouseMove = (e: MouseEvent) => {
      mouseX = e.clientX;
      mouseY = e.clientY;
      mouseActive = true;
    };
    const onMouseLeave = () => { mouseActive = false; };

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseleave', onMouseLeave);

    const draw = () => {
      frameRef.current = requestAnimationFrame(draw);
      ctx.clearRect(0, 0, W, H);

      // Background base
      ctx.fillStyle = '#0B0D12';
      ctx.fillRect(0, 0, W, H);

      // Draw orbs (background glow)
      for (const orb of orbs) {
        orb.x += orb.vx;
        orb.y += orb.vy;

        if (orb.x < -orb.radius) orb.x = W + orb.radius;
        if (orb.x > W + orb.radius) orb.x = -orb.radius;
        if (orb.y < -orb.radius) orb.y = H + orb.radius;
        if (orb.y > H + orb.radius) orb.y = -orb.radius;

        const gradient = ctx.createRadialGradient(orb.x, orb.y, 0, orb.x, orb.y, orb.radius);
        const c = orb.color;
        gradient.addColorStop(0, c + Math.floor(orb.alpha * 255).toString(16).padStart(2, '0'));
        gradient.addColorStop(1, 'transparent');

        ctx.fillStyle = gradient;
        ctx.fillRect(orb.x - orb.radius, orb.y - orb.radius, orb.radius * 2, orb.radius * 2);
      }

      // Update and draw particles
      const time = Date.now() * 0.001;

      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];

        // Mouse attraction
        if (mouseActive) {
          const dx = mouseX - p.x;
          const dy = mouseY - p.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < MOUSE_RADIUS && dist > 10) {
            const force = (MOUSE_RADIUS - dist) / MOUSE_RADIUS * 0.02;
            p.vx += (dx / dist) * force;
            p.vy += (dy / dist) * force;
          }
        }

        // Apply velocity with damping
        p.x += p.vx;
        p.y += p.vy;
        p.vx *= 0.99;
        p.vy *= 0.99;

        // Wrap around
        if (p.x < -10) p.x = W + 10;
        if (p.x > W + 10) p.x = -10;
        if (p.y < -10) p.y = H + 10;
        if (p.y > H + 10) p.y = -10;

        // Pulsing alpha
        const pulse = Math.sin(time * p.pulseSpeed + p.pulseOffset) * 0.15;
        const alpha = Math.max(0.1, Math.min(0.9, p.alpha + pulse));

        // Draw particle
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(${p.color.r}, ${p.color.g}, ${p.color.b}, ${alpha})`;
        ctx.fill();

        // Glow for larger particles
        if (p.radius > 1.5) {
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.radius * 3, 0, Math.PI * 2);
          const glowGradient = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.radius * 3);
          glowGradient.addColorStop(0, `rgba(${p.color.r}, ${p.color.g}, ${p.color.b}, ${alpha * 0.3})`);
          glowGradient.addColorStop(1, 'transparent');
          ctx.fillStyle = glowGradient;
          ctx.fill();
        }

        // Draw connections
        for (let j = i + 1; j < particles.length; j++) {
          const p2 = particles[j];
          const cdx = p.x - p2.x;
          const cdy = p.y - p2.y;
          const cDist = Math.sqrt(cdx * cdx + cdy * cdy);

          if (cDist < CONNECTION_DIST) {
            const lineAlpha = (1 - cDist / CONNECTION_DIST) * 0.15;
            const avgR = Math.floor((p.color.r + p2.color.r) / 2);
            const avgG = Math.floor((p.color.g + p2.color.g) / 2);
            const avgB = Math.floor((p.color.b + p2.color.b) / 2);

            ctx.beginPath();
            ctx.moveTo(p.x, p.y);
            ctx.lineTo(p2.x, p2.y);
            ctx.strokeStyle = `rgba(${avgR}, ${avgG}, ${avgB}, ${lineAlpha})`;
            ctx.lineWidth = 0.5;
            ctx.stroke();
          }
        }
      }

      // Draw mouse glow
      if (mouseActive) {
        const mGrad = ctx.createRadialGradient(mouseX, mouseY, 0, mouseX, mouseY, MOUSE_RADIUS);
        mGrad.addColorStop(0, 'rgba(128, 255, 151, 0.04)');
        mGrad.addColorStop(0.5, 'rgba(107, 183, 255, 0.02)');
        mGrad.addColorStop(1, 'transparent');
        ctx.fillStyle = mGrad;
        ctx.fillRect(mouseX - MOUSE_RADIUS, mouseY - MOUSE_RADIUS, MOUSE_RADIUS * 2, MOUSE_RADIUS * 2);
      }
    };

    draw();

    const onResize = () => {
      W = window.innerWidth;
      H = window.innerHeight;
      canvas.width = W;
      canvas.height = H;
    };
    window.addEventListener('resize', onResize);

    return () => {
      cancelAnimationFrame(frameRef.current);
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseleave', onMouseLeave);
      window.removeEventListener('resize', onResize);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        width: '100%',
        height: '100%',
        zIndex: 0,
        pointerEvents: 'none',
      }}
    />
  );
}

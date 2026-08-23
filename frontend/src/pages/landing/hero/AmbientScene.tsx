import { useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import * as THREE from 'three';

const PARTICLE_COUNT = 220;

function ParticleField() {
  const pointsRef = useRef<THREE.Points>(null);

  const positions = useMemo(() => {
    const array = new Float32Array(PARTICLE_COUNT * 3);
    for (let i = 0; i < PARTICLE_COUNT; i++) {
      array[i * 3] = (Math.random() - 0.5) * 12;
      array[i * 3 + 1] = (Math.random() - 0.5) * 8;
      array[i * 3 + 2] = (Math.random() - 0.5) * 6;
    }
    return array;
  }, []);

  useFrame((state) => {
    if (!pointsRef.current) return;
    // Slow, ambient drift only -- this is depth-of-field decoration behind a financial dashboard,
    // not something meant to be watched.
    pointsRef.current.rotation.y = state.clock.elapsedTime * 0.02;
  });

  return (
    <points ref={pointsRef}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      {/* #16A34A matches --m-success in index.css -- three.js can't consume a CSS custom
          property, so this hex has to be kept in sync with that token by hand. */}
      <pointsMaterial color="#16A34A" size={0.035} sizeAttenuation transparent opacity={0.55} />
    </points>
  );
}

/**
 * Ambient depth layer only -- never the dashboard itself (see the hero design spec's Non-goals).
 * Only ever reached via AmbientCanvas's React.lazy() boundary, so this file's three.js/
 * @react-three/fiber imports never land in the main landing-route bundle.
 */
export function AmbientScene() {
  return (
    <Canvas camera={{ position: [0, 0, 6], fov: 50 }} gl={{ alpha: true, antialias: true }}>
      <ParticleField />
    </Canvas>
  );
}

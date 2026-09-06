import { render, screen } from '@testing-library/react-native';
import { Text, View } from 'react-native';
import { TourTargetProvider, useRegisterTourTarget, useTourTarget } from './TourTargetRegistry';

function Registrar({ tourKey }: { tourKey: string }) {
  const register = useRegisterTourTarget(tourKey);
  return <View ref={register}><Text>registrar</Text></View>;
}

function Reader({ tourKey }: { tourKey: string }) {
  const target = useTourTarget(tourKey);
  return <Text>{target ? 'found' : 'missing'}</Text>;
}

describe('TourTargetRegistry', () => {
  it('lets a reader see a ref registered elsewhere in the tree', () => {
    render(
      <TourTargetProvider>
        <Registrar tourKey="home" />
        <Reader tourKey="home" />
      </TourTargetProvider>
    );
    expect(screen.getByText('found')).toBeTruthy();
  });

  it('reports missing for an unregistered key', () => {
    render(
      <TourTargetProvider>
        <Reader tourKey="nothing-registered" />
      </TourTargetProvider>
    );
    expect(screen.getByText('missing')).toBeTruthy();
  });
});

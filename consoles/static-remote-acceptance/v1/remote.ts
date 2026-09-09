export function mount(container: HTMLElement): void {
  const output = document.createElement('p');
  output.className = 'sf-static-remote-v1';
  output.textContent = 'Remote v1 executed';
  container.append(output);
}

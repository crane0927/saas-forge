import { AuthenticationApi, Configuration } from '@saas-forge/api-client';
import type { LoginRequest, Middleware } from '@saas-forge/api-client';

const configuration = new Configuration({
  basePath: 'https://api.saas.forge.test',
  middleware: [] satisfies Middleware[],
});
const authenticationApi: AuthenticationApi = new AuthenticationApi(configuration);
const loginRequest: LoginRequest = {
  email: 'developer@saas.forge.test',
  password: 'type-check-only',
};

void [authenticationApi, loginRequest];

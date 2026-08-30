import { AuthenticationApi, Configuration } from '@saas-forge/api-client';
import type { LoginRequest, Middleware } from '@saas-forge/api-client';

const configuration = new Configuration({
  basePath: 'https://api.saasforge.test',
  middleware: [] satisfies Middleware[],
});
const authenticationApi: AuthenticationApi = new AuthenticationApi(configuration);
const loginRequest: LoginRequest = {
  email: 'developer@saasforge.test',
  password: 'type-check-only',
};

void [authenticationApi, loginRequest];

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export type SweepConfig = {
  fromParty: string;
  toParty: string;
  maxBalance: number;
  minBalance: number;
};

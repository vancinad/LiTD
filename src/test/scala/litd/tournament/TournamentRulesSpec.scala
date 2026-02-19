package litd.tournament

import org.scalatest.funsuite.AnyFunSuite

final class TournamentRulesSpec extends AnyFunSuite {

  test("configuredMaxRounds must be within 1..15") {
    assert(!TournamentRules.isValidConfiguredMaxRounds(0))
    assert(TournamentRules.isValidConfiguredMaxRounds(1))
    assert(TournamentRules.isValidConfiguredMaxRounds(15))
    assert(!TournamentRules.isValidConfiguredMaxRounds(16))
  }

  test("nextEffectiveRound is 1 when no rounds exist") {
    assert(TournamentRules.nextEffectiveRound(None) == 1)
  }

  test("nextEffectiveRound increments from latest round") {
    assert(TournamentRules.nextEffectiveRound(Some(1)) == 2)
    assert(TournamentRules.nextEffectiveRound(Some(4)) == 5)
  }

  test("computeEffectiveMaxRounds uses min(configured, ceil(log2(players)))") {
    assert(TournamentRules.computeEffectiveMaxRounds(configuredMaxRounds = 8, registeredPlayerCount = 2) == 1)
    assert(TournamentRules.computeEffectiveMaxRounds(configuredMaxRounds = 8, registeredPlayerCount = 8) == 3)
    assert(TournamentRules.computeEffectiveMaxRounds(configuredMaxRounds = 8, registeredPlayerCount = 64) == 6)
    assert(TournamentRules.computeEffectiveMaxRounds(configuredMaxRounds = 5, registeredPlayerCount = 64) == 5)
  }

  test("registration status transition rules") {
    assert(RegistrationStatus.canTransition(RegistrationStatus.Registered, RegistrationStatus.Withdrawn))
    assert(RegistrationStatus.canTransition(RegistrationStatus.Withdrawn, RegistrationStatus.Registered))
    assert(!RegistrationStatus.canTransition(RegistrationStatus.Disqualified, RegistrationStatus.Registered))
    assert(!RegistrationStatus.canTransition(RegistrationStatus.Registered, RegistrationStatus.Registered))
  }

  test("time control validation bounds") {
    assert(!TournamentRules.isValidTimeControlInitialSeconds(0))
    assert(TournamentRules.isValidTimeControlInitialSeconds(180))
    assert(!TournamentRules.isValidTimeControlInitialSeconds(20000))

    assert(!TournamentRules.isValidTimeControlIncrementSeconds(-1))
    assert(TournamentRules.isValidTimeControlIncrementSeconds(2))
    assert(!TournamentRules.isValidTimeControlIncrementSeconds(400))
  }
}

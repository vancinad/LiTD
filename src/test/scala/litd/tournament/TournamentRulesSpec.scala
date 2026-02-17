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

  test("registration status transition rules") {
    assert(RegistrationStatus.canTransition(RegistrationStatus.Registered, RegistrationStatus.Withdrawn))
    assert(RegistrationStatus.canTransition(RegistrationStatus.Withdrawn, RegistrationStatus.Registered))
    assert(!RegistrationStatus.canTransition(RegistrationStatus.Disqualified, RegistrationStatus.Registered))
    assert(!RegistrationStatus.canTransition(RegistrationStatus.Registered, RegistrationStatus.Registered))
  }
}

